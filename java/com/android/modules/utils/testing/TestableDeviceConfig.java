/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.modules.utils.testing;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.modules.utils.testing.AbstractExtendedMockitoRule.AbstractBuilder;

import com.android.modules.utils.build.SdkLevel;

import org.junit.rules.TestRule;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * TestableDeviceConfig is a {@link StaticMockFixture} that uses ExtendedMockito to replace the real
 * implementation of DeviceConfig with essentially a local HashMap in the callers process. This
 * allows for unit testing that do not modify the real DeviceConfig on the device at all.
 */
public final class TestableDeviceConfig implements StaticMockFixture {

    private static final String TAG = TestableDeviceConfig.class.getSimpleName();

    private final Map<DeviceConfig.OnPropertiesChangedListener, Pair<String, Executor>>
            mOnPropertiesChangedListenerMap = new HashMap<>();
    private final Map<String, String> mKeyValueMap = new ConcurrentHashMap<>();

    /**
     * Clears out all local overrides.
     */
    public void clearDeviceConfig() {
        Log.i(TAG, "clearDeviceConfig()");
        mKeyValueMap.clear();
    }

    @Override
    public StaticMockitoSessionBuilder setUpMockedClasses(
            StaticMockitoSessionBuilder sessionBuilder) {
        sessionBuilder.spyStatic(DeviceConfig.class);
        return sessionBuilder;
    }

    @Override
    public void setUpMockBehaviors() {
        doAnswer((Answer<Void>) invocationOnMock -> {
            log(invocationOnMock);
            String namespace = invocationOnMock.getArgument(0);
            Executor executor = invocationOnMock.getArgument(1);
            DeviceConfig.OnPropertiesChangedListener onPropertiesChangedListener =
                    invocationOnMock.getArgument(2);
            mOnPropertiesChangedListenerMap.put(
                    onPropertiesChangedListener, new Pair<>(namespace, executor));
            return null;
        }).when(() -> DeviceConfig.addOnPropertiesChangedListener(
                anyString(), any(Executor.class),
                any(DeviceConfig.OnPropertiesChangedListener.class)));

        doAnswer((Answer<Boolean>) invocationOnMock -> {
            log(invocationOnMock);
            String namespace = invocationOnMock.getArgument(0);
            String name = invocationOnMock.getArgument(1);
            String value = invocationOnMock.getArgument(2);
            mKeyValueMap.put(getKey(namespace, name), value);
            invokeListeners(namespace, getProperties(namespace, name, value));
            return true;
        }).when(() -> DeviceConfig.setProperty(
                anyString(), anyString(), anyString(), anyBoolean()));

        if (SdkLevel.isAtLeastT()) {
            doAnswer((Answer<Boolean>) invocationOnMock -> {
                log(invocationOnMock);
                String namespace = invocationOnMock.getArgument(0);
                String name = invocationOnMock.getArgument(1);
                mKeyValueMap.remove(getKey(namespace, name));
                invokeListeners(namespace, getProperties(namespace, name, null));
                return true;
            }).when(() -> DeviceConfig.deleteProperty(anyString(), anyString()));

            doAnswer((Answer<Boolean>) invocationOnMock -> {
                log(invocationOnMock);
                Properties properties = invocationOnMock.getArgument(0);
                String namespace = properties.getNamespace();
                Map<String, String> keyValues = new ArrayMap<>();
                for (String name : properties.getKeyset()) {
                    String value = properties.getString(name, /* defaultValue= */ "");
                    mKeyValueMap.put(getKey(namespace, name), value);
                    keyValues.put(name.toLowerCase(), value);
                }
                invokeListeners(namespace, getProperties(namespace, keyValues));
                return true;
            }).when(() -> DeviceConfig.setProperties(any(Properties.class)));
        }

        doAnswer((Answer<String>) invocationOnMock -> {
            log(invocationOnMock);
            String namespace = invocationOnMock.getArgument(0);
            String name = invocationOnMock.getArgument(1);
            return mKeyValueMap.get(getKey(namespace, name));
        }).when(() -> DeviceConfig.getProperty(anyString(), anyString()));
        if (SdkLevel.isAtLeastR()) {
            doAnswer((Answer<Properties>) invocationOnMock -> {
                log(invocationOnMock);
                String namespace = invocationOnMock.getArgument(0);
                final int varargStartIdx = 1;
                Map<String, String> keyValues = new ArrayMap<>();
                if (invocationOnMock.getArguments().length == varargStartIdx) {
                    mKeyValueMap.entrySet().forEach(entry -> {
                        Pair<String, String> nameSpaceAndName = getNameSpaceAndName(entry.getKey());
                        if (!nameSpaceAndName.first.equals(namespace)) {
                            return;
                        }
                        keyValues.put(nameSpaceAndName.second.toLowerCase(), entry.getValue());
                    });
                } else {
                    for (int i = varargStartIdx; i < invocationOnMock.getArguments().length; ++i) {
                        String name = invocationOnMock.getArgument(i);
                        keyValues.put(name.toLowerCase(),
                            mKeyValueMap.get(getKey(namespace, name)));
                    }
                }
                return getProperties(namespace, keyValues);
            }).when(() -> DeviceConfig.getProperties(anyString(), ArgumentMatchers.<String>any()));
        }
    }

    @Override
    public void tearDown() {
        Log.i(TAG, "tearDown()");
        clearDeviceConfig();
        mOnPropertiesChangedListenerMap.clear();
    }

    private static String getKey(String namespace, String name) {
        return namespace + "/" + name;
    }

    private Pair<String, String> getNameSpaceAndName(String key) {
        final String[] values = key.split("/");
        return Pair.create(values[0], values[1]);
    }

    private void invokeListeners(String namespace, Properties properties) {
        for (DeviceConfig.OnPropertiesChangedListener listener :
                mOnPropertiesChangedListenerMap.keySet()) {
            if (namespace.equals(mOnPropertiesChangedListenerMap.get(listener).first)) {
                Log.d(TAG, "Calling listener " + listener + " for changes on namespace "
                        + namespace);
                mOnPropertiesChangedListenerMap.get(listener).second.execute(
                        () -> listener.onPropertiesChanged(properties));
            }
        }
    }

    private void log(InvocationOnMock invocation) {
        if (!Log.isLoggable(TAG, Log.VERBOSE)) {
            // Avoid stream allocation below if it's disabled...
            return;
        }
        // InvocationOnMock.toString() prints one argument per line, which would spam logcat
        try {
            Log.v(TAG, "answering " + invocation.getMethod().getName() + "("
                    + Arrays.stream(invocation.getArguments()).map(Object::toString)
                    .collect(Collectors.joining(", ")) + ")");
        } catch (Exception e) {
            // Fallback in case logic above fails
            Log.v(TAG, "answering " + invocation);
        }
    }

    private Properties getProperties(String namespace, String name, String value) {
        return getProperties(namespace, Collections.singletonMap(name.toLowerCase(), value));
    }

    private Properties getProperties(String namespace, Map<String, String> keyValues) {
        Properties.Builder builder = new Properties.Builder(namespace);
        keyValues.forEach((k, v) -> {
            builder.setString(k, v);
        });
        Properties properties = spy(builder.build());
        when(properties.getNamespace()).thenReturn(namespace);
        when(properties.getKeyset()).thenReturn(keyValues.keySet());
        when(properties.getBoolean(anyString(), anyBoolean())).thenAnswer(
                invocation -> {
                    log(invocation);
                    String key = invocation.getArgument(0);
                    boolean defaultValue = invocation.getArgument(1);
                    final String value = keyValues.get(key.toLowerCase());
                    if (value != null) {
                        return Boolean.parseBoolean(value);
                    } else {
                        return defaultValue;
                    }
                }
        );
        when(properties.getFloat(anyString(), anyFloat())).thenAnswer(
                invocation -> {
                    log(invocation);
                    String key = invocation.getArgument(0);
                    float defaultValue = invocation.getArgument(1);
                    final String value = keyValues.get(key.toLowerCase());
                    if (value != null) {
                        try {
                            return Float.parseFloat(value);
                        } catch (NumberFormatException e) {
                            return defaultValue;
                        }
                    } else {
                        return defaultValue;
                    }
                }
        );
        when(properties.getInt(anyString(), anyInt())).thenAnswer(
                invocation -> {
                    log(invocation);
                    String key = invocation.getArgument(0);
                    int defaultValue = invocation.getArgument(1);
                    final String value = keyValues.get(key.toLowerCase());
                    if (value != null) {
                        try {
                            return Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            return defaultValue;
                        }
                    } else {
                        return defaultValue;
                    }
                }
        );
        when(properties.getLong(anyString(), anyLong())).thenAnswer(
                invocation -> {
                    log(invocation);
                    String key = invocation.getArgument(0);
                    long defaultValue = invocation.getArgument(1);
                    final String value = keyValues.get(key.toLowerCase());
                    if (value != null) {
                        try {
                            return Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            return defaultValue;
                        }
                    } else {
                        return defaultValue;
                    }
                }
        );
        when(properties.getString(anyString(), nullable(String.class))).thenAnswer(
                invocation -> {
                    log(invocation);
                    String key = invocation.getArgument(0);
                    String defaultValue = invocation.getArgument(1);
                    final String value = keyValues.get(key.toLowerCase());
                    if (value != null) {
                        return value;
                    } else {
                        return defaultValue;
                    }
                }
        );

        return properties;
    }

    /**
     * <p>TestableDeviceConfigRule is a {@link TestRule} that wraps a {@link TestableDeviceConfig}
     * to set it up and tear it down automatically. This works well when you have no other static
     * mocks.</p>
     *
     * <p>TestableDeviceConfigRule should be defined as a rule on your test so it can clean up after
     * itself. Like the following:</p>
     * <pre class="prettyprint">
     * &#064;Rule
     * public final TestableDeviceConfigRule mTestableDeviceConfigRule =
     *     new TestableDeviceConfigRule(this);
     * </pre>
     */
    public static final class TestableDeviceConfigRule extends
            AbstractExtendedMockitoRule<TestableDeviceConfigRule, TestableDeviceConfigRuleBuilder> {

        /**
         * Creates the rule, initializing the mocks for the given test.
         */
        public TestableDeviceConfigRule(Object testClassInstance) {
            this(new TestableDeviceConfigRuleBuilder(testClassInstance)
                    .addStaticMockFixtures(TestableDeviceConfig::new));
        }

        /**
         * Creates the rule, without initializing the mocks.
         */
        public TestableDeviceConfigRule() {
            this(new TestableDeviceConfigRuleBuilder()
                    .addStaticMockFixtures(TestableDeviceConfig::new));
        }

        private TestableDeviceConfigRule(TestableDeviceConfigRuleBuilder builder) {
            super(builder);
        }
    }

    private static final class TestableDeviceConfigRuleBuilder extends
            AbstractBuilder<TestableDeviceConfigRule, TestableDeviceConfigRuleBuilder> {

        TestableDeviceConfigRuleBuilder(Object testClassInstance) {
            super(testClassInstance);
        }

        TestableDeviceConfigRuleBuilder() {
            super();
        }

        @Override
        public TestableDeviceConfigRule build() {
            return new TestableDeviceConfigRule(this);
        }
    }
}
