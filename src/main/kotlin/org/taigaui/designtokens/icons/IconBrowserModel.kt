package org.taigaui.designtokens.icons

internal fun filterIconNames(
    names: List<String>,
    query: String,
): List<String> {
    val search = query.trim()

    return if (search.isEmpty()) {
        names
    } else {
        names.filter { name -> name.contains(search, ignoreCase = true) }
    }
}

internal enum class IconBrowserSnippet(
    private val description: String,
) {
    NAME("Icon name"),
    ELEMENT("<tui-icon>"),
    ICON_START("iconStart"),
    ;

    fun render(name: String): String =
        when (this) {
            NAME -> name
            ELEMENT -> """<tui-icon icon="$name" />"""
            ICON_START -> """iconStart="$name""""
        }

    override fun toString(): String = description
}
