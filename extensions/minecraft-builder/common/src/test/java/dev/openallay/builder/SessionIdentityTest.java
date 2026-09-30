package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionIdentityTest {
    @Test void sameUuidDimensionReconnectNeverRebindsOldOperation() {
        Object connection = new Object(), player = new Object(), level = new Object(), server = new Object();
        UUID actor = UUID.randomUUID();
        SessionIdentity identity = new SessionIdentity(connection,player,level,server,actor,"minecraft:overworld");
        assertDoesNotThrow(() -> identity.requireClient(connection,player,level,server,actor,"minecraft:overworld"));
        assertThrows(BuilderException.class, () -> identity.requireClient(new Object(),player,level,server,actor,"minecraft:overworld"));
        assertThrows(BuilderException.class, () -> identity.requireClient(connection,new Object(),level,server,actor,"minecraft:overworld"));
        assertThrows(BuilderException.class, () -> identity.requireClient(connection,player,new Object(),server,actor,"minecraft:overworld"));
        assertThrows(BuilderException.class, () -> identity.requireClient(connection,player,level,new Object(),actor,"minecraft:overworld"));
        assertThrows(BuilderException.class, () -> identity.requireClient(connection,player,level,server,actor,"minecraft:the_nether"));
        assertThrows(BuilderException.class, () -> identity.requireClient(connection,player,level,server,UUID.randomUUID(),"minecraft:overworld"));
    }
    @Test void respawnServerPlayerAndLevelReplacementAreStale() {
        UUID actor = UUID.randomUUID();
        SessionIdentity identity = new SessionIdentity(new Object(),new Object(),new Object(),new Object(),actor,"minecraft:overworld");
        Object player = new Object(), level = new Object();
        identity.bindServer(player,level);
        assertDoesNotThrow(() -> identity.requireServer(player,level,actor,"minecraft:overworld"));
        assertThrows(BuilderException.class, () -> identity.requireServer(new Object(),level,actor,"minecraft:overworld"));
        assertThrows(BuilderException.class, () -> identity.requireServer(player,new Object(),actor,"minecraft:overworld"));
        assertThrows(BuilderException.class, () -> identity.requireServer(player,level,actor,"minecraft:the_nether"));
        assertThrows(IllegalStateException.class, () -> identity.bindServer(player,level));
    }
}
