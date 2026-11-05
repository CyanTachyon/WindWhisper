package moe.tachyon.windwhisper.config

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.SequenceStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.io.files.FileNotFoundException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.serializer
import moe.tachyon.windwhisper.logger.WindWhisperLogger
import moe.tachyon.windwhisper.workDir
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.typeOf

inline fun <reified T: Any> config(
    filename: String,
    default: T,
    readonly: Boolean = true,
    vararg listeners: (T, T)->Unit
): ConfigLoader<T> =
    ConfigLoader.createLoader(filename, default, readonly, typeOf<T>(), *listeners)

class ConfigLoader<T: Any> private constructor(
    private val default: T,
    private val filename: String,
    private val readonly: Boolean,
    val type: KType,
    private var listeners: MutableSet<(T, T)->Unit> = mutableSetOf()
)
{
    var config: T = default
        private set

    /**
     * 设置配置值，会触发监听器，但不会保存配置文件。
     *
     * 可视为更新内存中的配置值。可能由修改配置引起，也可能由重新加载配置引起。
     * 因此其只表示内存中的配置值变化，不会意味着修改配置文件。
     */
    private fun setValue0(value: T)
    {
        listeners.forEach {
            logger.warning("Error in config listener")
            {
                it(config, value)
            }
        }
        config = value
        logger.config("Config $filename changed to $value")
    }

    /**
     * 设置配置值，会触发监听器，并保存配置文件。
     *
     * 与[setValue0]的区别在于，[setValue0]表示更新内存中的配置值，而[setValue]表示修改配置文件。
     * 另外，若配置文件前后的值没有变化，则不会无视发生。
     *
     * @throws IllegalStateException 如果配置是只读的
     */
    fun setValue(value: T)
    {
        if (readonly) error("Config $filename is readonly, cannot set value")
        if (value == config) return // 如果值没有变化，则不触发监听器
        setValue0(value)
        saveConfig()
    }

    fun saveConfig() = saveConfig(filename, config, type)

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = config
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = setValue(value)

    @Suppress("UNCHECKED_CAST")
    fun reload()
    {
        logger.config("Reloading config $filename")

        try
        {
            setValue0(getConfig(filename, type) as T)
        }
        catch (_: FileNotFoundException)
        {
            setValue0(default)
            saveConfig()
        }
        catch (e: Throwable)
        {
            throw e
        }
    }

    @Suppress("unused", "MemberVisibilityCanBePrivate")
    companion object
    {
        private val logger by lazy { WindWhisperLogger.getLogger<ConfigLoader<*>>() }
        val configSerializer = Yaml(
            configuration = YamlConfiguration(
                sequenceStyle = SequenceStyle.Flow,
                strictMode = false,
                anchorsAndAliases = AnchorsAndAliases.Permitted(null),
            )
        )
        fun init() // 初始化所有配置
        {
            listOf(aiConfig, loggerConfig, networkConfig)
            reloadAll()
        }

        /**
         * [WeakReference] 采用弱引用, 避免不被回收
         * @author nullaqua
         */
        private val configMap: MutableMap<String, ConfigLoader<*>> =
            ConcurrentHashMap()

        fun configs() = configMap.keys
        fun reload(name: String) = getConfigLoader(name)?.reload()
        fun reloadAll() = configMap.values.forEach(ConfigLoader<*>::reload)
        fun getConfigLoader(name: String) = configMap[name]

        private fun addLoader(loader: ConfigLoader<*>)
        {
            if (configMap[loader.filename] != null) error("Loader already exists")
            configMap[loader.filename] = loader
        }

        fun <T: Any> createLoader(filename: String, default: T, readonly: Boolean, type: KType, vararg listeners: (T, T)->Unit) =
            ConfigLoader(default, filename, readonly, type, listeners.toHashSet()).also(Companion::addLoader)

        /// 配置文件加载 ///

        const val CONFIG_DIR = "configs"

        /**
         * 从配置文件中获取配置, 需要T是可序列化的.在读取失败时抛出错误
         * @param filename 配置文件名
         * @return 配置
         */
        inline fun <reified T> getConfig(filename: String): T =
            configSerializer.decodeFromString(getConfigFile(filename).readText())

        fun getConfig(filename: String, type: KType): Any? =
            configSerializer.decodeFromString(configSerializer.serializersModule.serializer(type), getConfigFile(filename).readText())

        /**
         * 从配置文件中获取配置, 需要T是可序列化的,在读取失败时返回默认值
         * @param filename 配置文件名
         * @param default 默认值
         * @return 配置
         */
        inline fun <reified T> getConfig(filename: String, default: T): T =
            runCatching { getConfig<T>(filename) }.getOrDefault(default)

        @Suppress("UNCHECKED_CAST")
        fun <T> getConfig(filename: String, default: T, type: KType): T =
            runCatching { getConfig(filename, type) }.getOrDefault(default) as T

        /**
         * 从配置文件中获取配置, 需要T是可序列化的,在读取失败时返回null
         * @param filename 配置文件名
         * @return 配置
         */
        inline fun <reified T> getConfigOrNull(filename: String): T? = getConfig(filename, null)
        fun getConfigOrNull(filename: String, type: KType): Any? = getConfig(filename, null, type)

        /**
         * 从配置文件中获取配置, 需要T是可序列化的,在读取失败时返回默认值,并将默认值写入配置文件
         * @param filename 配置文件名
         * @param default 默认值
         * @return 配置
         */
        inline fun <reified T> getConfigOrCreate(filename: String, default: T): T =
            getConfigOrNull<T>(filename) ?: default.also { saveConfig(filename, it) }

        fun getConfigOrCreate(filename: String, default: Any, type: KType): Any =
            getConfigOrNull(filename, type) ?: default.also { saveConfig(filename, default, type) }

        /**
         * 保存配置到文件
         * @param filename 配置文件名
         * @param config 配置
         */
        inline fun <reified T> saveConfig(filename: String, config: T) =
            createConfigFile(filename).writeText(configSerializer.encodeToString(configSerializer.serializersModule.serializer(), config))

        fun saveConfig(filename: String, config: Any, type: KType) =
            createConfigFile(filename).writeText(configSerializer.encodeToString(configSerializer.serializersModule.serializer(type), config))

        /**
         * 获取配置文件
         * @param filename 配置文件名
         * @return 配置文件
         */
        fun getConfigFile(filename: String): File = File(File(workDir, CONFIG_DIR), filename)

        /**
         * 获取配置文件, 如果不存在则创建
         * @param filename 配置文件名
         * @return 配置文件
         */
        fun createConfigFile(filename: String): File =
            getConfigFile(filename).also { it.parentFile.mkdirs(); it.createNewFile() }
    }
}
