package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.extension.JavascriptInvocationContext;
import dev.openallay.model.CancellationSignal;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

class DedicatedLinkageTest {
    @Test void disabledContributionDoesNotResolveClientClasses() throws Exception {
        URL classes=BuilderExtension.class.getProtectionDomain().getCodeSource().getLocation();
        ClassLoader parent=BuilderExtension.class.getClassLoader();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{classes},parent) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("net.minecraft.client."))throw new ClassNotFoundException("dedicated server: "+name);
                if(name.startsWith("dev.openallay.builder.")) {
                    synchronized(getClassLoadingLock(name)) {
                        Class<?> found=findLoadedClass(name);
                        if(found==null)found=findClass(name);
                        if(resolve)resolveClass(found);
                        return found;
                    }
                }
                return super.loadClass(name,resolve);
            }
        }) {
            Object extension=loader.loadClass("dev.openallay.builder.BuilderExtension").getConstructor(String.class).newInstance("fabric");
            assertNotNull(extension.getClass().getMethod("contribution").invoke(extension));
            var constructor=JavascriptInvocationContext.class.getDeclaredConstructor(ToolInvocationContext.class,CancellationSignal.class);
            constructor.setAccessible(true);
            var context=constructor.newInstance(ToolInvocationContext.developmentConsole("dedicated-test"),new CancellationSignal());
            Object participant=loader.loadClass("dev.openallay.builder.BuilderParticipant").getConstructor(String.class).newInstance("fabric");
            AutoCloseable scope=(AutoCloseable)participant.getClass().getMethod("open",JavascriptInvocationContext.class).invoke(participant,context);
            scope.close();
        }
    }
}
