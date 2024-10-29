/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package org.carapaceproxy.utils;

import static org.junit.Assert.assertTrue;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.util.Arrays;
import java.util.concurrent.Callable;
import org.junit.Assert;

/**
 * Utility for tests
 *
 * @author enrico.olivelli
 */
public class TestUtils {

    private static final int WAIT_FOR_CONDITION_DEFAULT_WAIT_SECONDS = 20;

    public static void waitForCondition(Callable<Boolean> condition, int seconds) throws Exception {
        waitForCondition(condition, null, seconds);
    }

    public static void waitForCondition(Callable<Boolean> condition) throws Exception {
        waitForCondition(condition, null, WAIT_FOR_CONDITION_DEFAULT_WAIT_SECONDS);
    }

    public static void waitForCondition(Callable<Boolean> condition, Callable<Void> callback, int seconds) throws Exception {
        try {
            long _start = System.currentTimeMillis();
            long millis = seconds * 1000;
            while (System.currentTimeMillis() - _start <= millis) {
                if (condition.call()) {
                    return;
                }
                if (callback != null) {
                    callback.call();
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException ee) {
            Assert.fail("test interrupted!");
            return;
        } catch (Exception ee) {
            Assert.fail("error while evalutaing condition:" + ee);
            return;
        }
        Assert.fail("condition not met in time!");
    }

    public static String deployResource(String resource, File tmpDir) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);) {
            Path path = new File(tmpDir, resource).toPath();
            Files.copy(in, path);
            return path.toAbsolutePath().toString();
        }
    }

    public static int getFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0);) {
            System.out.println("Got free ephemeral port " + s.getLocalPort());
            assertTrue(s.getLocalPort() > 0);
            return s.getLocalPort();
        }
    }

    public static void assertEqualsKey(Key key1, Key key2) {
        assertTrue(Arrays.equals(key1.getEncoded(), key2.getEncoded()));
    }

    /**
     * ** Copied from JUnit 4.13 ** *
     */
    public interface ThrowingRunnable {

        void run() throws Throwable;
    }

    /**
     * Asserts that {@code runnable} throws an exception of type {@code expectedThrowable} when executed. If it does not throw an exception, an {@link AssertionError} is thrown. If it throws the wrong
     * type of exception, an {@code AssertionError} is thrown describing the mismatch; the exception that was actually thrown can be obtained by calling {@link
     * AssertionError#getCause}.
     *
     * @param expectedThrowable the expected type of the exception
     * @param runnable a function that is expected to throw an exception when executed
     * @since 4.13
     */
    public static void assertThrows(Class<? extends Throwable> expectedThrowable, ThrowingRunnable runnable) {
        expectThrows(expectedThrowable, runnable);
    }

    /**
     * Asserts that {@code runnable} throws an exception of type {@code expectedThrowable} when executed. If it does, the exception object is returned. If it does not throw an exception, an
     * {@link AssertionError} is thrown. If it throws the wrong type of exception, an {@code
     * AssertionError} is thrown describing the mismatch; the exception that was actually thrown can be obtained by calling {@link AssertionError#getCause}.
     *
     * @param expectedThrowable the expected type of the exception
     * @param runnable a function that is expected to throw an exception when executed
     * @return the exception thrown by {@code runnable}
     * @since 4.13
     */
    public static <T extends Throwable> T expectThrows(Class<T> expectedThrowable, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable actualThrown) {
            if (expectedThrowable.isInstance(actualThrown)) {
                @SuppressWarnings("unchecked")
                T retVal = (T) actualThrown;
                return retVal;
            } else {
                String mismatchMessage = String.format("unexpected exception type thrown expected %s actual %s",
                        expectedThrowable.getSimpleName(), actualThrown.getClass().getSimpleName());

                // The AssertionError(String, Throwable) ctor is only available on JDK7.
                AssertionError assertionError = new AssertionError(mismatchMessage);
                assertionError.initCause(actualThrown);
                throw assertionError;
            }
        }
        String message = String.format("expected %s to be thrown, but nothing was thrown",
                expectedThrowable.getSimpleName());
        throw new AssertionError(message);
    }
}
