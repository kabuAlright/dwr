/*
 * Copyright 2007 Tim Peierls
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.directwebremoting.guice;

import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import static java.lang.reflect.Modifier.isStatic;

/**
 * @author Tim Peierls [tim at peierls dot net]
 * @deprecated Use {@link org.directwebremoting.guice.util.BindUtil}
 */
@Deprecated
public class BindUtil
{
    /**
     * For fluent-style decoration with one or more method bindings when
     * using {@link BindUtil#fromConstructor(Class, Key...)}.
     */
    public interface BindingProvider<T> extends Provider<T>
    {
        /**
         * Adds injection of a method defined by the given name and parameter
         * types (specified as Guice keys) to this provider.
         */
        BindingProvider<T> injecting(String methodName, Key<?>... paramKeys);
    }

    /**
     * Creates a chainable provider that constructs an instance of the
     * given type given a list of constructor parameter types, specified
     * as Guice keys. Construction errors are thrown immediately.
     */
    public static <T> BindingProvider<T> fromConstructor(Class<T> type, final Key<?>... keys)
    {
        return new ConstructorBindingProvider<T>(null, type, keys);
    }

    /**
     * Creates a chainable provider that constructs an instance of the
     * given type given a list of constructor parameter types, specified
     * as Guice keys. Construction errors are passed to {@code binder}
     * to be thrown at the end of all binding.
     */
    public static <T> BindingProvider<T> fromConstructor(Binder binder, Class<T> type, final Key<?>... keys)
    {
        return new ConstructorBindingProvider<T>(binder, type, keys);
    }

    /**
     * Creates a chainable provider that constructs an instance of {@code providedType}
     * using a factory method defined by {@code factoryType}, {@code methodName},
     * and a list of method parameter types specified as Guice keys. If the
     * method is not static an instance of the factory type is created and injected.
     * Construction errors are thrown immediately.
     */
    public static <T> BindingProvider<T> fromFactoryMethod(
        Class<T> providedType, Class<?> factoryType, String methodName, final Key<?>... keys)
    {
        return new FactoryMethodBindingProvider<T>(
            null, providedType, Key.get(factoryType), methodName, keys);
    }

    /**
     * Creates a chainable provider that constructs an instance of {@code providedType}
     * using a factory method defined by {@code factoryType}, {@code methodName},
     * and a list of method parameter types specified as Guice keys. If the
     * method is not static an instance of the factory type is created and injected.
     * Construction errors are passed to {@code binder} to be thrown at the end
     * of all binding.
     */
    public static <T> BindingProvider<T> fromFactoryMethod(
        Binder binder, Class<T> providedType,
        Class<?> factoryType, String methodName, final Key<?>... keys)
    {
        return new FactoryMethodBindingProvider<T>(
            binder, providedType, Key.get(factoryType), methodName, keys);
    }

    /**
     * Creates a chainable provider that constructs an instance of {@code providedType} by
     * calling method {@code methodName} of the type in {@code factoryKey} with
     * method parameter types specified as Guice keys. If the method is not static
     * an instance is created and injected using the factory key.
     * Construction errors are thrown immediately.
     */
    public static <T> BindingProvider<T> fromFactoryMethod(
        Class<T> providedType, Key<?> factoryKey, String methodName, final Key<?>... keys)
    {
        return new FactoryMethodBindingProvider<T>(
            null, providedType, factoryKey, methodName, keys);
    }

    /**
     * Creates a chainable provider that constructs an instance of {@code providedType} by
     * calling method {@code methodName} of the type in {@code factoryKey} with
     * method parameter types specified as Guice keys. If the method is not static
     * an instance is created and injected using the factory key.
     * Construction errors are passed to {@code binder} to be thrown at the end
     * of all binding.
     */
    public static <T> BindingProvider<T> fromFactoryMethod(
        Binder binder, Class<T> providedType,
        Key<?> factoryKey, String methodName, final Key<?>... keys)
    {
        return new FactoryMethodBindingProvider<T>(
            binder, providedType, factoryKey, methodName, keys);
    }


    //
    // Implementation classes
    //

    private abstract static class AbstractBindingProvider<T> implements BindingProvider<T>
    {
        protected AbstractBindingProvider(Binder binder, Class<T> type, Key<?>... keys)
        {
            this.binder = binder;
            this.type = type;
            this.keys = keys;
        }

        public final T get()
        {
            return get(theInjector);
        }

        protected abstract T get(Injector injector);

        public final BindingProvider<T> injecting(String methodName, Key<?>... paramKeys)
        {
            return new MethodBindingProvider<T>(this, type, methodName, paramKeys);
        }

        protected final Class<?>[] getTypes()
        {
            Class<?>[] types = new Class[keys.length];
            int i = 0;
            for (Key<?> key : keys)
            {
                types[i] = (Class<?>) key.getTypeLiteral().getType();
                i++;
            }
            return types;
        }

        protected final Object[] getValues(Injector injector)
        {
            Object[] values = new Object[keys.length];
            int i = 0;
            for (Key<?> key : keys)
            {
                Object param = injector.getInstance(key);
                values[i] = param;
                i++;
            }
            return values;
        }

        protected final Binder binder;
        protected final Class<T> type;
        protected final Key<?>[] keys;

        /**
         * Effectively immutable: Injected at end of bind-time,
         * read-only thereafter, and there is (or should be) a
         * happens-before edge between bind-time and subsequent reads.
         */
        @Inject private Injector theInjector;
    }

    private static class ConstructorBindingProvider<T> extends AbstractBindingProvider<T>
    {
        ConstructorBindingProvider(Binder binder, Class<T> type, Key<?>... keys)
        {
            super(binder, type, keys);

            Constructor<T> newConstructor = null;
            try
            {
                newConstructor = type.getConstructor(getTypes());
            }
            catch (NoSuchMethodException e)
            {
                if (binder == null)
                {
                    throw new IllegalArgumentException("no such constructor", e);
                }
                else
                {
                    binder.addError(e);
                }
            }

            this.constructor = newConstructor;
        }

        @Override
        public T get(Injector injector)
        {
            try
            {
                return constructor.newInstance(getValues(injector));
            }
            catch (InstantiationException e)
            {
                throw new IllegalStateException(e);
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException(e);
            }
            catch (InvocationTargetException e)
            {
                throw new IllegalStateException(e);
            }
        }

        private final Constructor<T> constructor;
    }

    private static class MethodBindingProvider<T> extends AbstractBindingProvider<T> {

        MethodBindingProvider(AbstractBindingProvider<T> prev,
                              Class<T> type, String methodName, Key<?>... keys)
        {
            super(prev.binder, type, keys);

            Method newMethod = null;
            try
            {
                newMethod = type.getMethod(methodName, getTypes());
            }
            catch (NoSuchMethodException e)
            {
                if (binder == null)
                {
                    throw new IllegalArgumentException("no such method", e);
                }
                else
                {
                    binder.addError(e);
                }
            }

            this.prev = prev;
            this.method = newMethod;
        }

        @Override
        public T get(Injector injector)
        {
            T target = prev.get(injector);
            try
            {
                method.invoke(target, getValues(injector));
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException(e);
            }
            catch (InvocationTargetException e)
            {
                throw new IllegalStateException(e);
            }
            return target;
        }

        private final AbstractBindingProvider<T> prev;
        private final Method method;
    }


    private static class FactoryMethodBindingProvider<T> extends AbstractBindingProvider<T>
    {
        FactoryMethodBindingProvider(Binder binder, Class<T> providedType, Key<?> factoryKey, String methodName, Key<?>... keys)
        {
            super(binder, providedType, keys);

            Method newMethod = null;
            try
            {
                Class<?> factoryType = (Class<?>) factoryKey.getTypeLiteral().getType();
                newMethod = factoryType.getMethod(methodName, getTypes());
                newMethod.getReturnType().asSubclass(providedType);
            }
            catch (NoSuchMethodException e)
            {
                if (binder == null)
                {
                    throw new IllegalArgumentException("no such method", e);
                }
                else
                {
                    binder.addError(e);
                }
            }
            catch (ClassCastException e)
            {
                if (binder == null)
                {
                    throw new IllegalArgumentException("bad return type", e);
                }
                else
                {
                    binder.addError(e);
                }
            }

            this.method = newMethod;
            this.factoryKey = factoryKey;
        }

        @Override
        public T get(Injector injector)
        {
            try
            {
                Object target = null;
                if (!isStatic(method.getModifiers()))
                {
                    target = injector.getInstance(factoryKey);
                }
                @SuppressWarnings("unchecked")
                T result = (T) method.invoke(target, getValues(injector));
                return result;
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException(e);
            }
            catch (InvocationTargetException e)
            {
                throw new IllegalStateException(e);
            }
        }

        private final Method method;
        private final Key<?> factoryKey;
    }
}