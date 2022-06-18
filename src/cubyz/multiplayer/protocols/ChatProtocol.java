package cubyz.multiplayer.protocols;

import cubyz.command.CommandExecutor;
import cubyz.multiplayer.Protocol;
import cubyz.multiplayer.UDPConnection;
import cubyz.multiplayer.server.Server;
import cubyz.multiplayer.server.User;
import cubyz.utils.Logger;

import java.nio.charset.StandardCharsets;

public class ChatProtocol extends Protocol {
	public ChatProtocol() {
		super((byte)10, true);
	}

	@Override
	public void receive(UDPConnection conn, byte[] data, int offset, int length) {
		String msg = new String(data, offset, length, StandardCharsets.UTF_8);
		if(conn instanceof User) {
			User user = (User)conn;
			if(msg.startsWith("/")) {
				CommandExecutor.execute(msg, user);
			} else {
				msg = "["+user.name+"] "+msg;
				sendToClients(msg);
			}
		} else {
			Logger.log("chat", msg, "\033[0;36m");
		}
	}

	public void send(UDPConnection conn, String msg) {
		byte[] data = msg.getBytes(StandardCharsets.UTF_8);
		conn.send(this, data);
	}

	public void sendToClients(String msg) {
		Logger.log("chat", msg, "\033[0;32m");
		synchronized(this) {
			for(User user : Server.users) {
				send(user, msg);
			}
		}
	}
}
