package io.a10vm.guest;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class LocalGuestService {
    static void register(String name, String descriptor, InvocationHandler handler) throws Exception {
        register(name, Class.forName(descriptor), descriptor, handler);
    }

    /**
     * Registers an endpoint for an interface whose class name does not match the
     * AIDL descriptor string. Android 15's connectivity module is jarjar'd, so
     * {@code android.net.IDnsResolver} exists only as
     * {@code android.net.connectivity.android.net.IDnsResolver}, while the
     * interface token the framework enforces is whatever that class's
     * {@code Stub.DESCRIPTOR} says. Passing the class keeps both sides equal.
     */
    static void register(String name, Class<?> api, String descriptor, InvocationHandler handler)
            throws Exception {
        Binder binder = endpoint(api, descriptor, handler);
        Class.forName("android.os.ServiceManager").getMethod("addService", String.class, IBinder.class)
                .invoke(null, name, binder);
    }

    static Binder endpoint(String descriptor, InvocationHandler handler) throws Exception {
        return endpoint(Class.forName(descriptor), descriptor, handler);
    }

    /**
     * The AIDL *object* for an endpoint this process provides, for calls that take
     * an interface argument (for example
     * {@code IConnectivityManager.registerNetworkAgent(INetworkAgent, ...)}).
     *
     * <p>{@link #endpoint} returns the {@link Binder} transport; passing that Binder
     * where an {@code INetworkAgent} is expected fails at the call site with
     * "argument 1 has type android.net.INetworkAgent, got
     * io.a10vm.guest.LocalGuestService$1" (instance 1793420681206, 2026-10-31).
     * Binder.queryLocalInterface returns the IInterface that attachInterface()
     * registered, which is exactly the proxy implementing the AIDL interface.
     */
    static Object localEndpoint(String descriptor, InvocationHandler handler) throws Exception {
        Binder binder = endpoint(descriptor, handler);
        Object token = descriptor;
        try {
            java.lang.reflect.Field field = Class.forName(descriptor + "$Stub").getField("DESCRIPTOR");
            token = field.get(null);
        } catch (Throwable ignored) { }
        Object local = binder.queryLocalInterface((String) token);
        if (local == null) throw new IllegalStateException("Guest endpoint is not an IInterface: " + descriptor);
        return local;
    }

    static Binder endpoint(Class<?> api, String descriptor, InvocationHandler handler)
            throws Exception {
        final String token = interfaceToken(api, descriptor);
        Map<Integer, Method> transactions = new HashMap<>();
        Class<?> stub = Class.forName(api.getName() + "$Stub");
        for (Method method : api.getMethods()) {
            try {
                Field code = stub.getDeclaredField("TRANSACTION_" + method.getName());
                code.setAccessible(true);
                transactions.put(code.getInt(null), method);
            } catch (NoSuchFieldException ignored) { }
        }
        Binder binder = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws android.os.RemoteException {
                Method method = transactions.get(code);
                if (method == null) return super.onTransact(code, data, reply, flags);
                try {
                    data.enforceInterface(token);
                    Class<?>[] types = method.getParameterTypes();
                    Object[] arguments = new Object[types.length];
                    for (int i = 0; i < types.length; ++i) {
                        // IDnsResolver.getResolverInfo has six out arrays, encoded as lengths.
                        if (method.getName().equals("getResolverInfo") && i > 0) {
                            int length = data.readInt();
                            if (length > 65536) throw new IllegalArgumentException("Resolver output size");
                            arguments[i] = length < 0 ? null : Array.newInstance(types[i].getComponentType(), length);
                        } else arguments[i] = read(data, types[i], method.getGenericParameterTypes()[i]);
                    }
                    Object result = handler.invoke(null, method, arguments);
                    if (reply != null && (flags & IBinder.FLAG_ONEWAY) == 0) {
                        reply.writeNoException();
                        write(reply, method.getReturnType(), method.getGenericReturnType(), result);
                        if (method.getName().equals("getResolverInfo"))
                            for (int i = 1; i < types.length; ++i) write(reply, types[i], types[i], arguments[i]);
                    }
                    return true;
                } catch (Throwable error) {
                    if (error instanceof InvocationTargetException) error = error.getCause();
                    if (reply != null && (flags & IBinder.FLAG_ONEWAY) == 0)
                        reply.writeException(error instanceof Exception ? (Exception) error : new IllegalStateException(error));
                    else System.err.println(descriptor + "." + method.getName() + ": " + error);
                    return true;
                }
            }
        };
        IInterface service = (IInterface) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api},
            (proxy, method, args) -> {
                if ("asBinder".equals(method.getName())) return binder;
                if ("toString".equals(method.getName())) return descriptor;
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                return handler.invoke(proxy, method, args);
            });
        binder.attachInterface(service, token);
        return binder;
    }

    /**
     * The interface token the client enforces. Modern AIDL stores it in
     * {@code Stub.DESCRIPTOR} (and writes it as {@code android$net$IDnsResolver},
     * not with dots), so read it from the generated class when possible.
     */
    private static String interfaceToken(Class<?> api, String fallback) {
        try {
            Class<?> stub = Class.forName(api.getName() + "$Stub", false, api.getClassLoader());
            Field field = stub.getDeclaredField("DESCRIPTOR");
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        } catch (Throwable ignored) { }
        return fallback;
    }

    private static Object read(Parcel data, Class<?> type, Type generic) throws Exception {
        if (type == int.class) return data.readInt();
        if (type == long.class) return data.readLong();
        if (type == boolean.class) return data.readInt() != 0;
        if (type == float.class) return data.readFloat();
        if (type == String.class) return data.readString();
        if (type == byte[].class) return data.createByteArray();
        if (type == int[].class) return data.createIntArray();
        if (type == long[].class) return data.createLongArray();
        if (type == String[].class) return data.createStringArray();
        if (type == List.class) {
            Class<?> item = listItem(generic);
            if (item == String.class) return data.createStringArrayList();
            if (item != null && Parcelable.class.isAssignableFrom(item))
                return data.createTypedArrayList((Parcelable.Creator<?>) item.getField("CREATOR").get(null));
            return data.readArrayList(LocalGuestService.class.getClassLoader());
        }
        if (type == Map.class) return data.readHashMap(LocalGuestService.class.getClassLoader());
        if (IInterface.class.isAssignableFrom(type))
            return Class.forName(type.getName() + "$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, data.readStrongBinder());
        if (type == IBinder.class) return data.readStrongBinder();
        if (type.isArray() && Parcelable.class.isAssignableFrom(type.getComponentType()))
            return data.createTypedArray((Parcelable.Creator<?>) type.getComponentType().getField("CREATOR").get(null));
        if (Parcelable.class.isAssignableFrom(type))
            return data.readInt() == 0 ? null : ((Parcelable.Creator<?>) type.getField("CREATOR").get(null)).createFromParcel(data);
        throw new IllegalArgumentException("Guest Binder argument " + type.getName());
    }

    @SuppressWarnings("unchecked")
    private static void write(Parcel reply, Class<?> type, Type generic, Object value) {
        if (type == void.class) return;
        if (type == int.class) reply.writeInt((Integer) value);
        else if (type == long.class) reply.writeLong((Long) value);
        else if (type == boolean.class) reply.writeInt((Boolean) value ? 1 : 0);
        else if (type == String.class) reply.writeString((String) value);
        else if (type == byte[].class) reply.writeByteArray((byte[]) value);
        else if (type == int[].class) reply.writeIntArray((int[]) value);
        else if (type == long[].class) reply.writeLongArray((long[]) value);
        else if (type == String[].class) reply.writeStringArray((String[]) value);
        else if (type == IBinder.class) reply.writeStrongBinder((IBinder) value);
        else if (type == List.class) {
            Class<?> item = listItem(generic);
            if (item == String.class) reply.writeStringList((List<String>) value);
            else if (item != null && Parcelable.class.isAssignableFrom(item))
                reply.writeTypedList((List<? extends Parcelable>) value);
            else reply.writeList((List<?>) value);
        }
        else if (type == Map.class) reply.writeMap((Map<?, ?>) value);
        // AIDL methods that return a Parcelable array (for example
        // android.apex.IApexService.getActivePackages() -> ApexInfo[]). Without
        // this branch every such call throws "Guest Binder result ..." and the
        // caller sees a RemoteException instead of an empty list. The value is
        // created at runtime with Array.newInstance(), so the placement code does
        // not need android.apex types on javac's class path (they are @hide).
        else if (type.isArray() && Parcelable.class.isAssignableFrom(type.getComponentType()))
            reply.writeTypedArray((Parcelable[]) value, 0);
        else if (Parcelable.class.isAssignableFrom(type)) {
            reply.writeInt(value == null ? 0 : 1);
            if (value != null) ((Parcelable) value).writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
        } else throw new IllegalArgumentException("Guest Binder result " + type.getName());
    }

    private static Class<?> listItem(Type generic) {
        if (!(generic instanceof ParameterizedType)) return null;
        Type item = ((ParameterizedType) generic).getActualTypeArguments()[0];
        return item instanceof Class<?> ? (Class<?>) item : null;
    }

    static Object call(Object target, String api, String name, Object... arguments) throws Exception {
        for (Method method : Class.forName(api).getMethods())
            if (method.getName().equals(name) && method.getParameterTypes().length == arguments.length)
                return method.invoke(target, arguments);
        throw new NoSuchMethodException(api + "." + name);
    }
}
