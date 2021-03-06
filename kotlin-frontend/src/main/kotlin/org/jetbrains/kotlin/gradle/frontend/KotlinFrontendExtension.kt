package org.jetbrains.kotlin.gradle.frontend

import groovy.lang.*
import org.gradle.api.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.frontend.config.*
import org.jetbrains.kotlin.gradle.frontend.rollup.*
import org.jetbrains.kotlin.gradle.frontend.webpack.*
import java.util.*

open class KotlinFrontendExtension(val project: Project) : GroovyObjectSupport() {
    init {
        val mc = ExpandoMetaClass(KotlinFrontendExtension::class.java, false, true)
        mc.initialize()
        metaClass = mc
    }

    private val _bundlers = linkedMapOf(
            "webpack" to WebPackBundler,
            "rollup" to RollupBundler
    )

    val bundlers: Map<String, Bundler<*>>
        get() = Collections.unmodifiableMap(_bundlers)

    @Input
    var sourceMaps: Boolean = false

    var bundlesDirectory: Any = project.buildDir.resolve("bundle")

    fun bundles(): List<BundleConfig> = Collections.unmodifiableList(
            bundleBuilders.map { p ->
                val (id, builder) = p

                val config = _bundlers[id]?.createConfig(project) ?: throw GradleException("bundle $id is not supported")
                for (configurator in defaultBundleConfigurators) {
                    configurator(config)
                }

                builder(config)

                config
            }
    )

    @Internal
    private val defaultBundleConfigurators = ArrayList<BundleConfig.() -> Unit>()

    @Internal
    private val bundleBuilders = ArrayList<Pair<String, BundleConfig.() -> Unit>>()

    fun allBundles(block: BundleConfig.() -> Unit) {
        defaultBundleConfigurators += block
    }

    fun allBundles(block: Closure<*>) {
        defaultBundleConfigurators += {
            block.delegate = this
            block.call()
        }
    }

    fun <C : BundleConfig> bundle(id: String, configure: BundleConfig.() -> Unit) {
        bundleBuilders += Pair(id, configure)
    }

    fun bundle(id: String, closure: Closure<*>) {
        bundleBuilders += Pair<String, BundleConfig.() -> Unit>(id, {
            closure.delegate = this
            closure.call()
        })
    }

    fun <T : Bundler<*>> bundler(id: String, instance: T) {
        if (id in _bundlers) {
            throw GradleException("Bundler with $id is already registered")
        }
        _bundlers[id] = instance
    }

    @Suppress("unused") // groovy magic method - don't change name/signature
    fun methodMissing(name: String, args: Any?): Any? {
        if (name.endsWith("Bundle") && args is Array<*>) {
            val bundlerId = name.removeSuffix("Bundle")
            val arg = args.singleOrNull()

            when (arg) {
                is Closure<*> -> return bundle(bundlerId, arg)
                is Function1<*, *> -> @Suppress("UNCHECKED_CAST") return bundle<BundleConfig>(bundlerId, arg as Function1<BundleConfig, Unit>)
            }
        }

        project.extensions.findByName(name)?.let { ext ->
            if (args is Array<*>) {
                val arg = args.singleOrNull()

                when (arg) {
                    is Closure<*> -> {
                        arg.delegate = ext
                        return arg.call()
                    }
                }
            }
        }

        throw MissingMethodException(name, KotlinFrontendExtension::class.java, args as? Array<*> ?: arrayOf(args))
    }
}