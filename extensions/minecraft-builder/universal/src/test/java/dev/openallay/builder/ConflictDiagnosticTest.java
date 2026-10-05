package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ConflictDiagnosticTest {
    @Test void oversizedImagesAreHashedNotDumpedAndWitnessEmitsExactlyOnce() {
        StringBuilder text=new StringBuilder();
        for(int i=0;i<ConflictDiagnostic.IMAGE_LIMIT+1;i++)text.append('x');
        String oversized=text.toString();
        Logger logger=Logger.getLogger(ConflictDiagnostic.LOGGER_NAME);
        List<String> messages=new ArrayList<>();
        Handler handler=new Handler() {
            @Override public void publish(LogRecord record){messages.add(record.getMessage());}
            @Override public void flush(){}
            @Override public void close(){}
        };
        logger.addHandler(handler);
        try {
            ConflictDiagnostic witness=new ConflictDiagnostic("write-apply",new BlockPosition(1,2,3),
                    oversized,oversized,oversized,"frame","operation","world","minecraft:overworld",true,false);
            witness.emit(); witness.emit();
            assertEquals(1,messages.size()); assertTrue(messages.get(0).length()<4096);
            JsonObject record=JsonParser.parseString(messages.get(0).substring(ConflictDiagnostic.PREFIX.length())).getAsJsonObject();
            for(String key:new String[]{"before","intended","current"}) {
                JsonObject image=record.getAsJsonObject(key);
                assertFalse(image.get("complete").getAsBoolean()); assertFalse(image.has("json"));
                assertEquals(oversized.length(),image.get("characters").getAsInt());
                assertEquals(sha256(oversized),image.get("sha256").getAsString());
            }
            assertTrue(record.get("ownerThread").getAsBoolean());
            assertFalse(record.get("workerThreadAtCapture").getAsBoolean());
            assertEquals(1,record.getAsJsonObject("position").get("x").getAsInt());
        } finally {logger.removeHandler(handler);}
    }
    private static String sha256(String value) {
        try {
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();
            for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
            return hex.toString();
        } catch(java.security.NoSuchAlgorithmException failure){throw new AssertionError(failure);}
    }
}
