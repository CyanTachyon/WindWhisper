package moe.tachyon.windwhisper

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import java.io.File
import java.io.InputStream

@Suppress("unused")
object Loader
{
    const val CYAN_LOGO = "logo/Cyan.logo.txt"

    /**
     * 获取资源文件
     * @param path 资源路径
     * @return 资源文件输入流
     */
    fun getResource(path: String): InputStream?
    {
        if (path.startsWith("/")) return Loader::class.java.getResource(path)?.openStream()
        return Loader::class.java.getResource("/$path")?.openStream()
    }

    fun mergeConfigs(vararg configs: File) =
        configs.map { it.readText() }
            .map { Yaml.default.parseToYamlNode(it) }
            .reduce { acc, element -> mergeConfig(acc, element) }
    /**
     * 合并多个配置文件(yaml)若有冲突以前者为准
     */
    fun mergeConfigs(vararg configs: InputStream) =
        configs.map { it.readAllBytes() }
            .map { Yaml.default.parseToYamlNode(it.decodeToString()) }
            .reduce { acc, element -> mergeConfig(acc, element) }

    private fun mergeConfig(a: YamlNode, b: YamlNode): YamlNode
    {
        if (a is YamlMap && b is YamlMap)
        {
            val map = mutableMapOf<YamlScalar, YamlNode>()
            a.entries.forEach { (k, v) ->
                val bk = b.entries[k]
                if (bk != null) map[k] = mergeConfig(v, bk)
                else map[k] = v
            }
            b.entries.forEach { (k, v) ->
                if (a.entries[k] == null) map[k] = v
            }
            return YamlMap(map, a.path)
        }
        return a
    }
}