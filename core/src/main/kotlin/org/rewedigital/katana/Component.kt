package org.rewedigital.katana

import org.rewedigital.katana.Declaration.Type.*
import org.rewedigital.katana.Key.ClassKey
import org.rewedigital.katana.Key.NameKey
import org.rewedigital.katana.internal.Logger

/**
 * Defines a component.
 *
 * @see Component
 */
fun createComponent(vararg modules: Module) =
    createComponent(modules = modules.asIterable())

/**
 * Defines a component.
 *
 * @see Component
 */
fun createComponent(vararg components: Component) =
    createComponent(dependsOn = components.asIterable())

/**
 * Defines a component.
 *
 * @see Component
 */
fun createComponent(modules: Iterable<Module> = emptyList(),
                    dependsOn: Iterable<Component> = emptyList()): Component {

    val moduleDeclarations = modules.map { module -> module.declarations }.fold {
        Logger.info { "Registering declaration $it" }
    }
    val componentDeclarations = dependsOn.map { component -> component.declarations }
    val allDeclarations = (componentDeclarations + moduleDeclarations).fold()

    if (allDeclarations.isEmpty()) {
        throw ComponentException("No declarations found (did you forget to pass modules and/or parent components?)")
    }

    return Component(moduleDeclarations, dependsOn)
}

/**
 * Along with [Modules][Module] a Component is the heart of dependency injection via Katana.
 *
 * It performs the actual injection and will also hold the singleton instances of dependencies declared as singletons.
 * This is an important aspect of Katana! As long as the same Component reference is used for injection, the same
 * singleton instances are reused. Once the Component is eligible for garbage collection so are the instances hold by
 * this component. The developer is responsible for holding a Component reference and releasing it when necessary. This
 * design was chosen in contrast to other DI libraries that for instance work with a global, singleton state to prevent
 * accidental memory leaks.
 *
 * However great care should be taken when dependent Components are specified via `dependsOn` at [createComponent]. If
 * dependent Components are specified and the current Component has a greater scope (lifespan) than the other Components,
 * references to these Components are still held. Dependent Components should always have an equal or greater scope
 * than the current Component.
 *
 * @see createComponent
 * @see inject
 * @see injectNow
 * @see canInject
 * @see Module
 */
class Component internal constructor(internal val declarations: Declarations,
                                     dependsOn: Iterable<Component>) {

    private val instances = Katana.environmentContext.mapFactory().create<Key, Any>()
    val context = ComponentContext.of(this, dependsOn)

    init {
        // Initialize eager singletons
        declarations.values
            .filter { it.type == EAGER_SINGLETON }
            .forEach { declaration ->
                instances[declaration.key] = declaration.provider(context) as Any
            }
    }

    @Suppress("UNCHECKED_CAST", "MemberVisibilityCanBePrivate")
    internal fun <T> thisComponentInjectByKey(key: Key, internal: Boolean): Injection<T>? {
        val declaration = declarations[key]
        if (declaration == null || (declaration.internal && !internal)) {
            return null
        } else {
            try {
                Logger.info { "Injecting dependency for ${key.stringIdentifier}" }
                val instance = when (declaration.type) {
                    FACTORY -> declaration.provider(context).also {
                        Logger.debug { "Created instance for ${key.stringIdentifier}" }
                    }
                    SINGLETON -> {
                        instances[key].also {
                            Logger.debug { "Returning existing singleton instance for ${key.stringIdentifier}" }
                        } ?: declaration.provider(context).also { newInstance ->
                            Logger.debug { "Created singleton instance for ${key.stringIdentifier}" }
                            instances[key] = newInstance as Any
                        }
                    }
                    EAGER_SINGLETON -> instances[key]
                        ?: throw KatanaException("Eager singleton was not properly initialized")
                }
                return Injection(instance as T)
            } catch (e: KatanaException) {
                throw e
            } catch (e: Exception) {
                throw InstanceCreationException("Could not instantiate $declaration", e)
            }
        }
    }

    internal fun thisComponentCanInject(key: Key, internal: Boolean) =
        declarations.filter { if (!internal) !it.value.internal else true }.containsKey(key)

    internal fun canInject(key: Key) =
        context.canInject(key)

    internal fun <T> injectByKey(key: Key) =
        context.injectByKey<T>(key)

    /**
     * Returns `true` if this component is capable of injecting requested dependency.
     */
    inline fun <reified T> canInject(name: String? = null) =
        context.canInject<T>(name)

    /**
     * Injects requested dependency lazily.
     * Should be used like:
     *
     * ```
     * val myDependency: MyDependency by component.inject()
     * ```
     *
     * @see injectNow
     * @throws InjectionException
     * @throws InstanceCreationException
     */
    inline fun <reified T> inject(name: String? = null) =
        context.inject<T>(name)

    /**
     * Injects requested dependency immediately.
     *
     * @see inject
     * @throws InjectionException
     * @throws InstanceCreationException
     */
    inline fun <reified T> injectNow(name: String? = null) =
        context.injectNow<T>(name)
}

/**
 * ComponentContext is used internally to represent the total set of [Components][Component] and thus possible
 * injections for the current context consisting of the current Component and all Components that have been specified
 * via `dependsOn` (see [createComponent]).
 */
class ComponentContext internal constructor(private val thisComponent: Component,
                                            private val dependsOn: Iterable<Component>) {

    fun <T> injectByKey(key: Key, internal: Boolean = false): T {
        val injection = thisComponent.thisComponentInjectByKey<T>(key, internal)
        return when {
            injection != null -> injection.value
            else -> {
                val component = dependsOn.find { component -> component.canInject(key) }
                    ?: throw InjectionException("No binding found for ${key.stringIdentifier}")
                component.injectByKey(key) as T
            }
        }
    }

    fun canInject(key: Key, internal: Boolean = false): Boolean =
        when {
            thisComponent.thisComponentCanInject(key = key, internal = internal) -> true
            else -> dependsOn.any { component -> component.canInject(key) }
        }

    inline fun <reified T> canInject(name: String? = null, internal: Boolean = false) =
        canInject(key = Key.of(T::class.java, name), internal = internal)

    inline fun <reified T> inject(name: String? = null, internal: Boolean = false) = lazy {
        injectNow<T>(name = name, internal = internal)
    }

    inline fun <reified T> injectNow(name: String? = null, internal: Boolean = false) =
        injectByKey<T>(key = Key.of(T::class.java, name), internal = internal)

    internal companion object {

        fun of(thisComponent: Component, dependsOn: Iterable<Component>) =
            ComponentContext(thisComponent, dependsOn)
    }
}

private fun Iterable<Declarations>.fold(each: ((Declaration<*>) -> Unit)? = null): Declarations =
    fold(Katana.environmentContext.mapFactory().create()) { acc, currDeclarations ->
        currDeclarations.entries.forEach { entry ->
            val existingDeclaration = acc[entry.key]
            existingDeclaration?.let { declaration ->
                if (!declaration.internal) throw OverrideException(entry.value, declaration)
            }
            each?.invoke(entry.value)
        }
        acc.apply { putAll(currDeclarations) }
    }

private val Key.stringIdentifier
    get() = when (this) {
        is ClassKey<*> -> "class ${clazz.name}"
        is NameKey -> "name $name"
    }

internal class Injection<T>(val value: T)
