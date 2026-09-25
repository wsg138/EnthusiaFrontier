package org.enthusia.tempchallenges.paper;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/**
 * Class-loader-safe LuckPerms adapter. Portable ownership is recognized only
 * from an exact, positive, context-free user node; wildcard or inherited
 * permission resolution is never treated as proof of ownership.
 */
public final class LuckPermsEntitlementGateway {
    private final JavaPlugin plugin;

    public LuckPermsEntitlementGateway(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        return luckPerms != null && luckPerms.isEnabled();
    }

    public boolean hasExplicit(UUID uuid, String permission) {
        if (!available()) return false;
        try {
            Object api = api();
            Object userManager = call(api, "getUserManager");
            Object user = invoke(userManager, "getUser", uuid);
            return user != null && ownsExplicitNode(user, permission);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("LuckPerms entitlement verification failed: " + exception.getMessage());
            return false;
        }
    }

    public void ensure(UUID uuid, String permission, BiConsumer<Boolean, String> completion) {
        withUser(uuid, (user, loadError) -> {
            if (loadError != null || user == null) {
                completion.accept(false, loadError == null ? "LuckPerms user unavailable" : loadError);
                return;
            }
            try {
                if (!ownsExplicitNode(user, permission)) addPermissionNode(user, permission);
                saveUser(user, completion);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                completion.accept(false, exception.getMessage());
            }
        });
    }

    public void revoke(UUID uuid, String permission, BiConsumer<Boolean, String> completion) {
        withUser(uuid, (user, loadError) -> {
            if (loadError != null || user == null) {
                completion.accept(false, loadError == null ? "LuckPerms user unavailable" : loadError);
                return;
            }
            try {
                Object nodeMap = call(user, "data");
                for (Object node : nodes(user)) {
                    if (isExactGlobalPositiveNode(node, permission)) invokeCompatible(nodeMap, "remove", node);
                }
                saveUser(user, completion);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                completion.accept(false, exception.getMessage());
            }
        });
    }

    private void withUser(UUID uuid, BiConsumer<Object, String> completion) {
        if (!available()) {
            completion.accept(null, "LuckPerms unavailable");
            return;
        }
        try {
            Object api = api();
            Object userManager = call(api, "getUserManager");
            Object loaded = invoke(userManager, "getUser", uuid);
            if (loaded != null) {
                completion.accept(loaded, null);
                return;
            }
            Object future = invoke(userManager, "loadUser", uuid);
            if (!(future instanceof CompletionStage<?> stage)) {
                completion.accept(null, "LuckPerms loadUser did not return CompletionStage");
                return;
            }
            stage.whenComplete((user, error) -> completion.accept(user,
                    error == null ? null : String.valueOf(error.getMessage())));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            completion.accept(null, exception.getMessage());
        }
    }

    private boolean ownsExplicitNode(Object user, String permission) throws ReflectiveOperationException {
        for (Object node : nodes(user)) {
            if (isExactGlobalPositiveNode(node, permission)) return true;
        }
        return false;
    }

    private Collection<?> nodes(Object user) throws ReflectiveOperationException {
        Object value = call(user, "getNodes");
        if (!(value instanceof Collection<?> collection)) {
            throw new ReflectiveOperationException("LuckPerms getNodes returned unexpected type");
        }
        return collection;
    }

    private boolean isExactGlobalPositiveNode(Object node, String permission) throws ReflectiveOperationException {
        Object key = call(node, "getKey");
        Object value = call(node, "getValue");
        Object contexts = call(node, "getContexts");
        Object empty = call(contexts, "isEmpty");
        return permission.equalsIgnoreCase(String.valueOf(key))
                && Boolean.TRUE.equals(value)
                && Boolean.TRUE.equals(empty);
    }

    private void addPermissionNode(Object user, String permission) throws ReflectiveOperationException {
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null) throw new ReflectiveOperationException("LuckPerms plugin disappeared");
        ClassLoader loader = luckPerms.getClass().getClassLoader();
        Class<?> permissionNode = Class.forName("net.luckperms.api.node.types.PermissionNode", true, loader);
        Object builder = permissionNode.getMethod("builder", String.class).invoke(null, permission);
        builder = builder.getClass().getMethod("value", boolean.class).invoke(builder, true);
        Object node = builder.getClass().getMethod("build").invoke(builder);
        Object nodeMap = call(user, "data");
        invokeCompatible(nodeMap, "add", node);
    }

    private void saveUser(Object user, BiConsumer<Boolean, String> completion) throws ReflectiveOperationException {
        Object api = api();
        Object userManager = call(api, "getUserManager");
        Object future = invokeCompatible(userManager, "saveUser", user);
        if (future instanceof CompletionStage<?> stage) {
            stage.whenComplete((ignored, error) -> completion.accept(error == null,
                    error == null ? null : String.valueOf(error.getMessage())));
        } else {
            completion.accept(true, null);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object api() throws ReflectiveOperationException {
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) throw new ReflectiveOperationException("LuckPerms unavailable");
        Class<?> apiClass = Class.forName("net.luckperms.api.LuckPerms", true, luckPerms.getClass().getClassLoader());
        RegisteredServiceProvider registration = Bukkit.getServicesManager().getRegistration((Class) apiClass);
        if (registration == null || registration.getProvider() == null) {
            throw new ReflectiveOperationException("LuckPerms service unavailable");
        }
        return registration.getProvider();
    }

    private Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private Object invoke(Object target, String name, UUID uuid) throws ReflectiveOperationException {
        return target.getClass().getMethod(name, UUID.class).invoke(target, uuid);
    }

    private Object invokeCompatible(Object target, String name, Object argument) throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            if (method.getParameterTypes()[0].isAssignableFrom(argument.getClass())) {
                return method.invoke(target, argument);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + '#' + name);
    }
}
