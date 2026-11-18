package com.kigtts.plugins.debug

import com.kigtts.plugin.api.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.Locale

/** UI probes use the public contribution protocol; the host owns their appearance. */
class UiDebugPlugin(
    initialCounts: Map<UiSlot, Int> = emptyMap(),
    private val save: (Map<UiSlot, Int>) -> Unit = {},
    initialStyles: Map<UiSlot, UiButtonStyle> = emptyMap(),
    private val saveStyles: (Map<UiSlot, UiButtonStyle>) -> Unit = {},
) : KigPlugin {
    override val descriptor = PluginDescriptor("debug.ui", "界面调试插件", "在指定扩展区域插入指定数量的按钮或菜单项", version = "0.1.1")
    override val localization = PluginLocalization("zh-CN", listOf("zh-CN", "zh-CN-x-casual", "en", "ko", "ja"))
    private var strings = PluginStrings(emptyMap())
    private var environment: PluginEnvironment? = null
    private val counts = UiSlot.entries.associateWith { initialCounts[it]?.coerceIn(0, 20) ?: 0 }.toMutableMap()
    private val styles = UiSlot.entries.associateWith { initialStyles[it] ?: UiButtonStyle.ICON }.toMutableMap()
    private var configuration: File? = null
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        strings = PluginStrings.fromAssets(environment.assets)
        val file = environment.storage.file("config/ui-probes.json"); configuration = file
        if (!file.isFile) return
        runCatching { PluginJson.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject }.getOrNull()?.let { state ->
            UiSlot.entries.forEach { slot ->
                counts[slot] = state["counts"]?.jsonObject?.get(slot.name)?.jsonPrimitive?.intOrNull?.coerceIn(0, 20) ?: 0
                styles[slot] = runCatching { UiButtonStyle.valueOf(state["styles"]?.jsonObject?.get(slot.name)?.jsonPrimitive?.content.orEmpty()) }.getOrDefault(UiButtonStyle.ICON)
            }
        }
    }
    private fun persist() {
        val file = configuration ?: return
        file.parentFile!!.mkdirs()
        val next = File(file.parentFile, "${file.name}.tmp")
        next.writeText(buildJsonObject {
            put("counts", buildJsonObject { counts.forEach { (slot, count) -> put(slot.name, count) } })
            put("styles", buildJsonObject { styles.forEach { (slot, style) -> put(slot.name, style.name) } })
        }.toString(), Charsets.UTF_8)
        java.nio.file.Files.move(next.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    override val contributions get() = contributionsFor(environment?.locale ?: "zh-CN")
    override fun contributions(context: PluginContext) = contributionsFor(context.locale)
    private fun contributionsFor(locale: String) = PluginContributions(
        actions = UiSlot.entries.flatMap { slot ->
            List(counts.getValue(slot)) { index ->
                UiAction(slot.name + "/" + index, label(slot, locale) + " · " + (index + 1), slot, PluginIcon.STAR,
                    if (slot == UiSlot.EDITOR_MENU) UiPresentation.MENU_ITEM else UiPresentation.BUTTON, index, styles.getValue(slot))
            }
        },
        settingsEntries = listOf(SettingsEntry("config", strings.text("title", locale, fallback = "界面调试"), "config", PluginIcon.SETTINGS,
            cardId = "development", cardTitle = strings.text("category", locale, fallback = "开发与调试"), symbol = "bug_report")),
    )

    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome {
        val locale = context.locale
        fun text(key: String, vararg args: Pair<String, String>) = strings.text(key, locale, args.toMap())
        if (actionId == "reset") {
            counts.keys.forEach { counts[it] = 0 }; save(counts.toMap()); persist()
            return PluginOutcome.RefreshUi
        }
        val parts = actionId.split("/")
        val slot = UiSlot.entries.firstOrNull { it.name == parts.firstOrNull() }
        if (slot != null && parts.size == 2) {
            if (parts[1] == "style") {
                styles[slot] = if (styles.getValue(slot) == UiButtonStyle.ICON) UiButtonStyle.ICON_TEXT else UiButtonStyle.ICON
                saveStyles(styles.toMap()); persist()
                return PluginOutcome.RefreshUi
            }
            val delta = when (parts[1]) { "add" -> 1; "remove" -> -1; else -> 0 }
            if (delta != 0) {
                counts[slot] = (counts.getValue(slot) + delta).coerceIn(0, 20)
                save(counts.toMap()); persist()
                return PluginOutcome.RefreshUi
            }
            return PluginOutcome.Menu(text("entry", "slot" to label(slot, locale)), listOf(
                PageAction("info", text("show_dialog"), PluginIcon.INFO), PageAction("config", text("open_page"), PluginIcon.SETTINGS)))
        }
        return when (actionId) {
            "web-demo" -> PluginOutcome.PresentContent(SubtitleContent.Html(html = """
                <!doctype html><html lang="${Locale.forLanguageTag(locale).toLanguageTag()}"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <style>body{font:20px system-ui;margin:24px;background:#e0f4f3;color:#00383b}button{border:0;border-radius:100px;padding:16px 24px;background:#00696e;color:white;font:inherit}</style>
                <h2>${escapeHtml(text("web.title"))}</h2><p>${escapeHtml(text("web.info"))}</p>
                <button onclick='Kigtts.postMessage(JSON.stringify({action:"web-click",inputs:{source:"demo"}}))'>${escapeHtml(text("web.button"))}</button></html>
            """.trimIndent(), description = text("web.demo"), allowedActions = listOf("web-click")))
            "web-click" -> PluginOutcome.Message(text("web.success"))
            "config" -> PluginOutcome.OpenPage("config")
            "info" -> PluginOutcome.Dialog(text("dialog.title"), text("dialog.text"))
            else -> PluginOutcome.Message(text("unknown"))
        }
    }

    override fun page(pageId: String, context: PluginContext): PluginPage? {
        if (pageId != "config") return null
        val locale = context.locale
        fun text(key: String, vararg args: Pair<String, String>) = strings.text(key, locale, args.toMap())
        return PluginPage("config", text("title"), buildList {
            add(PageBlock.Text(text("test.title"), heading = true))
            add(PageBlock.Text(text("test.info")))
            add(PageBlock.Actions(listOf(PageAction("reset", text("reset")))))
            add(PageBlock.Actions(listOf(PageAction("web-demo", text("web.show"), PluginIcon.TEXT))))
            UiSlot.entries.forEach { slot ->
                val label = label(slot, locale)
                val selectedStyle = if (styles.getValue(slot) == UiButtonStyle.ICON) "style.icon" else "style.icon_text"
                add(PageBlock.Group(label, listOf(
                    PageBlock.Text(text("count", "count" to counts.getValue(slot).toString())),
                    PageBlock.Text(text(selectedStyle)),
                    PageBlock.Actions(listOf(
                        PageAction(slot.name + "/remove", text("remove", "slot" to label), PluginIcon.MENU),
                        PageAction(slot.name + "/add", text("add", "slot" to label), PluginIcon.ADD),
                        PageAction(slot.name + "/style", text("style.switch", "slot" to label), PluginIcon.TEXT)
                    ))
                )))
            }
        })
    }

    private fun label(slot: UiSlot, locale: String) = strings.text("slot.${slot.name}", locale, fallback = slot.name)
    private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
