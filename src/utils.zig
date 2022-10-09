const std = @import("std");
const Allocator = std.mem.Allocator;

const main = @import("main.zig");

pub const Compression = struct {
	pub fn deflate(allocator: Allocator, data: []const u8) ![]u8 {
		var result = std.ArrayList(u8).init(allocator);
		var comp = try std.compress.deflate.compressor(main.threadAllocator, result.writer(), .{.level = .default_compression});
		try comp.write(data);
		try comp.close();
		comp.deinit();
		return result.toOwnedSlice();
	}

	pub fn inflate(allocator: Allocator, data: []const u8) ![]u8 {
		var stream = std.io.fixedBufferStream(data);
		var decomp = try std.compress.deflate.decompressor(main.threadAllocator, stream.reader(), null);
		defer decomp.deinit();
		return try decomp.reader().readAllAlloc(allocator, std.math.maxInt(usize));
	}

	pub fn pack(sourceDir: std.fs.IterableDir, writer: anytype) !void {
		var comp = try std.compress.deflate.compressor(main.threadAllocator, writer, .{.level = .default_compression});
		defer comp.deinit();
		var walker = try sourceDir.walk(main.threadAllocator);
		defer walker.deinit();

		while(try walker.next()) |entry| {
			if(entry.kind == .File) {
				var relPath = entry.path;
				var len: [4]u8 = undefined;
				std.mem.writeIntBig(u32, &len, @intCast(u32, relPath.len));
				_ = try comp.write(&len);
				_ = try comp.write(relPath);

				var file = try sourceDir.dir.openFile(relPath, .{});
				defer file.close();
				var fileData = try file.readToEndAlloc(main.threadAllocator, std.math.maxInt(u32));
				defer main.threadAllocator.free(fileData);

				std.mem.writeIntBig(u32, &len, @intCast(u32, fileData.len));
				_ = try comp.write(&len);
				_ = try comp.write(fileData);
			}
		}
		try comp.close();
	}

	pub fn unpack(outDir: std.fs.Dir, input: []const u8) !void {
		var stream = std.io.fixedBufferStream(input);
		var decomp = try std.compress.deflate.decompressor(main.threadAllocator, stream.reader(), null);
		defer decomp.deinit();
		var reader = decomp.reader();
		const _data = try reader.readAllAlloc(main.threadAllocator, std.math.maxInt(usize));
		defer main.threadAllocator.free(_data);
		var data = _data;
		while(data.len != 0) {
			var len = std.mem.readIntBig(u32, data[0..4]);
			data = data[4..];
			var path = data[0..len];
			data = data[len..];
			len = std.mem.readIntBig(u32, data[0..4]);
			data = data[4..];
			var fileData = data[0..len];
			data = data[len..];

			var splitter = std.mem.splitBackwards(u8, path, "/");
			_ = splitter.first();
			try outDir.makePath(splitter.rest());
			var file = try outDir.createFile(path, .{});
			defer file.close();
			try file.writeAll(fileData);
		}
	}
};

/// A simple binary heap.
/// Thread safe and blocking.
/// Expects T to have a `biggerThan(T) bool` function
pub fn BlockingMaxHeap(comptime T: type) type {
	return struct {
		const initialSize = 16;
		size: usize,
		array: []T,
		waitingThreads: std.Thread.Condition,
		waitingThreadCount: u32 = 0,
		mutex: std.Thread.Mutex,
		allocator: Allocator,
		closed: bool = false,

		pub fn init(allocator: Allocator) !*@This() {
			var self = try allocator.create(@This());
			self.* = @This() {
				.size = 0,
				.array = try allocator.alloc(T, initialSize),
				.waitingThreads = .{},
				.mutex = .{},
				.allocator = allocator,
			};
			return self;
		}

		pub fn deinit(self: *@This()) void {
			self.mutex.lock();
			self.closed = true;
			// Wait for all waiting threads to leave before cleaning memory.
			self.waitingThreads.broadcast();
			while(self.waitingThreadCount != 0) {
				self.mutex.unlock();
				std.time.sleep(1000000);
				self.mutex.lock();
			}
			self.mutex.unlock();
			self.allocator.free(self.array);
			self.allocator.destroy(self);
		}

		/// Moves an element from a given index down the heap, such that all children are always smaller than their parents.
		fn siftDown(self: *@This(), _i: usize) void {
			std.debug.assert(!self.mutex.tryLock()); // The mutex should be locked when calling this function.
			var i = _i;
			while(2*i + 2 < self.size) {
				var biggest = if(self.array[2*i + 1].biggerThan(self.array[2*i + 2])) 2*i + 1 else 2*i + 2;
				// Break if all childs are smaller.
				if(self.array[i].biggerThan(self.array[biggest])) return;
				// Swap it:
				var local = self.array[biggest];
				self.array[biggest] = self.array[i];
				self.array[i] = local;
				// goto the next node:
				i = biggest;
			}
		}

		/// Moves an element from a given index up the heap, such that all children are always smaller than their parents.
		fn siftUp(self: *@This(), _i: usize) void {
			std.debug.assert(!self.mutex.tryLock()); // The mutex should be locked when calling this function.
			var i = _i;
			while(i > 0) {
				var parentIndex = (i - 1)/2;
				if(!self.array[i].biggerThan(self.array[parentIndex])) break;
				var local = self.array[parentIndex];
				self.array[parentIndex] = self.array[i];
				self.array[i] = local;
				i = parentIndex;
			}
		}

		/// Needs to be called after updating the priority of all elements.
		pub fn updatePriority(self: *@This()) void {
			self.mutex.lock();
			defer self.mutex.unlock();
			for(self.array[0..self.size/2]) |_, i| {
				self.siftDown(i);
			}
		}

		/// Returns the i-th element in the heap. Useless for most applications.
		pub fn get(self: *@This(), i: usize) ?T {
			std.debug.assert(!self.mutex.tryLock()); // The mutex should be locked when calling this function.
			if(i >= self.size) return null;
			return self.array[i];
		}

		/// Adds a new element to the heap.
		pub fn add(self: *@This(), elem: T) !void {
			self.mutex.lock();
			defer self.mutex.unlock();

			if(self.size == self.array.len) {
				try self.increaseCapacity(self.size*2);
			}
			self.array[self.size] = elem;
			self.siftUp(self.size);
			self.size += 1;

			self.waitingThreads.signal();
		}

		fn removeIndex(self: *@This(), i: usize) void {
			std.debug.assert(!self.mutex.tryLock()); // The mutex should be locked when calling this function.
			self.size -= 1;
			self.array[i] = self.array[self.size];
			self.siftDown(i);
		}

		/// Returns the biggest element and removes it from the heap.
		/// If empty blocks until a new object is added or the datastructure is closed.
		pub fn extractMax(self: *@This()) !T {
			self.mutex.lock();
			defer self.mutex.unlock();

			while(true) {
				if(self.size == 0) {
					self.waitingThreadCount += 1;
					self.waitingThreads.wait(&self.mutex);
					self.waitingThreadCount -= 1;
				} else {
					var ret = self.array[0];
					self.removeIndex(0);
					return ret;
				}
				if(self.closed) {
					return error.Closed;
				}
			}
		}

		fn increaseCapacity(self: *@This(), newCapacity: usize) !void {
			self.array = try self.allocator.realloc(self.array, newCapacity);
		}
	};
}

pub const ThreadPool = struct {
	const Task = struct {
		cachedPriority: f32,
		self: *anyopaque,
		vtable: *const VTable,

		fn biggerThan(self: Task, other: Task) bool {
			return self.cachedPriority > other.cachedPriority;
		}
	};
	pub const VTable = struct {
		getPriority: *const fn(*anyopaque) f32,
		isStillNeeded: *const fn(*anyopaque) bool,
		run: *const fn(*anyopaque) void,
		clean: *const fn(*anyopaque) void,
	};
	const refreshTime: u32 = 100; // The time after which all priorities get refreshed in milliseconds.

	threads: []std.Thread,
	loadList: *BlockingMaxHeap(Task),
	allocator: Allocator,

	pub fn init(allocator: Allocator, threadCount: usize) !ThreadPool {
		var self = ThreadPool {
			.threads = try allocator.alloc(std.Thread, threadCount),
			.loadList = try BlockingMaxHeap(Task).init(allocator),
			.allocator = allocator,
		};
		for(self.threads) |*thread, i| {
			thread.* = try std.Thread.spawn(.{}, run, .{self});
			var buf: [64]u8 = undefined;
			try thread.setName(try std.fmt.bufPrint(&buf, "Worker Thread {}", .{i+1}));
		}
		return self;
	}

	pub fn deinit(self: ThreadPool) void {
		// Clear the remaining tasks:
		self.loadList.mutex.lock();
		for(self.loadList.array[0..self.loadList.size]) |task| {
			task.vtable.clean(task.self);
		}
		self.loadList.mutex.unlock();

		self.loadList.deinit();
		for(self.threads) |thread| {
			thread.join();
		}
		self.allocator.free(self.threads);
	}

	fn run(self: ThreadPool) void {
		// In case any of the tasks wants to allocate memory:
		var gpa = std.heap.GeneralPurposeAllocator(.{.thread_safe=false}){};
		main.threadAllocator = gpa.allocator();
		defer if(gpa.deinit()) {
			@panic("Memory leak");
		};

		var lastUpdate = std.time.milliTimestamp();
		while(true) {
			{
				var task = self.loadList.extractMax() catch break;
				task.vtable.run(task.self);
			}

			if(std.time.milliTimestamp() -% lastUpdate > refreshTime) {
				lastUpdate = std.time.milliTimestamp();
				if(self.loadList.mutex.tryLock()) {
					{
						defer self.loadList.mutex.unlock();
						var i: u32 = 0;
						while(i < self.loadList.size) {
							var task = self.loadList.array[i];
							if(!task.vtable.isStillNeeded(task.self)) {
								self.loadList.removeIndex(i);
								task.vtable.clean(task.self);
							} else {
								task.cachedPriority = task.vtable.getPriority(task.self);
								i += 1;
							}
						}
					}
					self.loadList.updatePriority();
				}
			}
		}
	}

	pub fn addTask(self: ThreadPool, task: *anyopaque, vtable: *const VTable) !void {
		try self.loadList.add(Task {
			.cachedPriority = vtable.getPriority(task),
			.vtable = vtable,
			.self = task,
		});
	}

	pub fn clear(self: ThreadPool) void {
		// Clear the remaining tasks:
		self.loadList.mutex.lock();
		for(self.loadList.array[0..self.loadList.size]) |task| {
			task.vtable.clean(task.self);
		}
		self.loadList.mutex.unlock();
		// Wait for the in-progress tasks to finish:
		while(true) {
			if(self.loadList.mutex.tryLock()) {
				defer self.loadList.mutex.unlock();
				if(self.loadList.waitingThreadCount == self.threads.len) {
					break;
				}
			}
			std.time.sleep(1000000);
		}
	}

	pub fn queueSize(self: ThreadPool) usize {
		self.loadList.mutex.lock();
		defer self.loadList.mutex.unlock();
		return self.loadList.size;
	}
};

pub fn GenericInterpolation(comptime elements: comptime_int) type {
	const frames: usize = 8;
	return struct {
		lastPos: [frames][elements]f64,
		lastVel: [frames][elements]f64,
		lastTimes: [frames]i16,
		frontIndex: u32,
		currentPoint: i32,
		outPos: [elements]f64,
		outVel: [elements]f64,

		pub fn initPosition(self: *@This(), initialPosition: *[elements]f64) void {
			std.mem.copy(f64, &self.outPos, initialPosition);
			std.mem.set([elements]f64, &self.lastPos, self.outPos);
			std.mem.set(f64, &self.outVel, 0);
			std.mem.set([elements]f64, &self.lastVel, self.outVel);
			self.frontIndex = 0;
			self.currentPoint = -1;
		}

		pub fn init(self: *@This(), initialPosition: *[elements]f64, initialVelocity: *[elements]f64) void {
			std.mem.copy(f64, &self.outPos, initialPosition);
			std.mem.set([elements]f64, &self.lastPos, self.outPos);
			std.mem.copy(f64, &self.outVel, initialVelocity);
			std.mem.set([elements]f64, &self.lastVel, self.outVel);
			self.frontIndex = 0;
			self.currentPoint = -1;
		}

		pub fn updatePosition(self: *@This(), pos: *[elements]f64, vel: *[elements]f64, time: i16) void {
			self.frontIndex = (self.frontIndex + 1)%frames;
			std.mem.copy(f64, &self.lastPos[self.frontIndex], pos);
			std.mem.copy(f64, &self.lastVel[self.frontIndex], vel);
			self.lastTimes[self.frontIndex] = time;
		}

		fn evaluateSplineAt(_t: f64, tScale: f64, p0: f64, _m0: f64, p1: f64, _m1: f64) [2]f64 {
			//  https://en.wikipedia.org/wiki/Cubic_Hermite_spline#Unit_interval_(0,_1)
			const t = _t/tScale;
			const m0 = _m0*tScale;
			const m1 = _m1*tScale;
			const t2 = t*t;
			const t3 = t2*t;
			const a0 = p0;
			const a1 = m0;
			const a2 = -3*p0 - 2*m0 + 3*p1 - m1;
			const a3 = 2*p0 + m0 - 2*p1 + m1;
			return [_]f64 {
				a0 + a1*t + a2*t2 + a3*t3, // value
				(a1 + 2*a2*t + 3*a3*t2)/tScale, // first derivative
			};
		}

		fn interpolateCoordinate(self: *@This(), i: u32, t: f64, tScale: f64) void {
			if(self.outVel[i] == 0 and self.lastVel[self.currentPoint][i] == 0) {
				self.outPos += (self.lastPos[self.currentPoint][i] - self.outPos[i])*t/tScale;
			} else {
				// Use cubic interpolation to interpolate the velocity as well.
				const newValue = evaluateSplineAt(t, tScale, self.outPos[i], self.outVel[i], self.lastPos[self.currentPoint][i], self.lastVel[self.currentPoint][i]);
				self.outPos = newValue[0];
				self.outVel = newValue[1];
			}
		}

		fn determineNextDataPoint(self: *@This(), time: i16, lastTime: *i16) void {
			if(self.currentPoint != -1 and self.lastTimes[self.currentPoint] -% time <= 0) {
				// Jump to the last used value and adjust the time to start at that point.
				lastTime.* = self.lastTimes[self.currentPoint];
				std.mem.copy(f64, &self.outPos, &self.lastPos[self.currentPoint]);
				std.mem.copy(f64, &self.outVel, &self.lastVel[self.currentPoint]);
				self.currentPoint = -1;
			}

			if(self.currentPoint == -1) {
				// Need a new point:
				var smallestTime: i16 = std.math.maxInt(i16);
				var smallestIndex: i32 = -1;
				for(self.lastTimes) |_, i| {
					//                              ↓ Only using a future time value that is far enough away to prevent jumping.
					if(self.lastTimes[i] -% time >= 50 and self.lastTimes[i] -% time < smallestTime) {
						smallestTime = self.lastTimes[i] -% time;
						smallestIndex = i;
					}
				}
				self.currentPoint = smallestIndex;
			}
		}

		pub fn update(self: *@This(), time: i16, _lastTime: i16) void {
			var lastTime = _lastTime;
			self.determineNextDataPoint(time, &lastTime);

			var deltaTime = @intToFloat(f64, time -% lastTime)/1000;
			if(deltaTime < 0) {
				std.log.err("Experienced time travel. Current time: {} Last time: {}", .{time, lastTime});
				deltaTime = 0;
			}

			if(self.currentPoint == -1) {
				for(self.outPos) |*pos, i| {
					// Just move on with the current velocity.
					pos.* += self.outVel[i]*deltaTime;
					// Add some drag to prevent moving far away on short connection loss.
					self.outVel[i] *= std.math.pow(f64, 0.5, deltaTime);
				}
			} else {
				const tScale = @intToFloat(f64, self.lastTimes[self.currentPoint] -% lastTime)/1000;
				const t = deltaTime;
				for(self.outPos) |_, i| {
					self.interpolateCoordinate(i, t, tScale);
				}
			}
		}

		pub fn updateIndexed(self: *@This(), time: i16, _lastTime: i16, indices: []u16, coordinatesPerIndex: comptime_int) void {
			var lastTime = _lastTime;
			self.determineNextDataPoint(time, &lastTime);

			var deltaTime = @intToFloat(f64, time -% lastTime)/1000;
			if(deltaTime < 0) {
				std.log.err("Experienced time travel. Current time: {} Last time: {}", .{time, lastTime});
				deltaTime = 0;
			}

			if(self.currentPoint == -1) {
				for(indices) |i| {
					const index = i*coordinatesPerIndex;
					var j: u32 = 0;
					while(j < coordinatesPerIndex): (j += 1) {
						// Just move on with the current velocity.
						self.outPos[index + j] += self.outVel[index + j]*deltaTime;
						// Add some drag to prevent moving far away on short connection loss.
						self.outVel[index + j] *= std.math.pow(f64, 0.5, deltaTime);
					}
				}
			} else {
				const tScale = @intToFloat(f64, self.lastTimes[self.currentPoint] -% lastTime)/1000;
				const t = deltaTime;
				for(indices) |i| {
					const index = i*coordinatesPerIndex;
					var j: u32 = 0;
					while(j < coordinatesPerIndex): (j += 1) {
						self.interpolateCoordinate(index + j, t, tScale);
					}
				}
			}
		}
	};
}

pub const TimeDifference = struct {
	difference: i16 = 0,
	firstValue: bool = true,

	pub fn addDataPoint(self: *TimeDifference, time: i16) void {
		const currentTime = @intCast(i16, std.time.milliTimestamp() & 65535);
		const timeDifference = currentTime -% time;
		if(self.firstValue) {
			self.difference = timeDifference;
			self.firstValue = false;
		}
		if(timeDifference -% self.difference > 0) {
			self.difference +%= 1;
		} else if(timeDifference -% self.difference < 0) {
			self.difference -%= 1;
		}
	}
};