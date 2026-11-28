use crate::{actor::server_actor::ServerMessage, debug, info, message::Message};
use std::sync::mpsc::Sender;

use crate::message::MessageHandler;

pub struct LoginHandler;

impl MessageHandler<ServerMessage> for LoginHandler {
    fn get_code(&self) -> u32 {
        1
    }

    fn handle(&self, message: &mut Message, sender: Sender<ServerMessage>) {
        let response = message.read_int8();

        if response != 1 {
            // The server says why: INVALIDPASS, INVALIDUSERNAME, SVRFULL...
            let _ = sender.send(ServerMessage::LoginRejected(message.read_string()));
            let _ = sender.send(ServerMessage::LoginStatus(false));
            return;
        }

        info!("Login successful");
        let greeting = message.read_string();
        debug!("Server greeting: {:?}", greeting);

        let _ = sender.send(ServerMessage::LoginStatus(true));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::message::framed;

    #[test]
    fn a_refused_login_carries_the_servers_reason() {
        let (tx, rx) = std::sync::mpsc::channel();
        let mut message = framed(|m| {
            m.write_int8(0).write_string("INVALIDPASS");
        });

        LoginHandler.handle(&mut message, tx);
        assert!(matches!(
            rx.try_recv(),
            Ok(ServerMessage::LoginRejected(reason)) if reason == "INVALIDPASS"
        ));
        assert!(matches!(
            rx.try_recv(),
            Ok(ServerMessage::LoginStatus(false))
        ));
    }
}
