package org.taigaui.designtokens.events

internal data class EventPluginBinding(
    val source: String,
    val event: String,
    val modifiers: List<EventPluginModifier>,
) {
    val combinedBehavior: String
        get() = "Handles $event: ${modifiers.joinToString(separator = "; ") { modifier -> modifier.behavior }}."

    companion object {
        fun parse(attributeName: String): EventPluginBinding? =
            attributeName
                .takeIf { name -> name.startsWith('(') && name.endsWith(')') }
                ?.let { name ->
                    val source = name.substring(1, name.lastIndex)
                    val parts = source.split('.')
                    val firstModifierIndex = parts.indexOfFirst { part -> EventPluginModifier.parse(part) != null }

                    firstModifierIndex
                        .takeIf { index -> index > 0 }
                        ?.let { index ->
                            val modifierParts = parts.drop(index)
                            val modifiers = modifierParts.mapNotNull(EventPluginModifier::parse)

                            modifiers
                                .takeIf { parsed ->
                                    parsed.size == modifierParts.size &&
                                        parsed.map(EventPluginModifier::source).distinct().size == parsed.size
                                }?.let {
                                    EventPluginBinding(
                                        source = name,
                                        event = parts.take(index).joinToString("."),
                                        modifiers = modifiers,
                                    )
                                }
                        }
                }
    }
}

internal data class GlobalEventPluginBinding(
    val source: String,
    val target: String,
    val event: String,
)

internal object GlobalEventPluginBindingSupport {
    fun parse(attributeName: String): GlobalEventPluginBinding? =
        attributeName
            .takeIf { name -> name.startsWith('(') && name.endsWith(')') }
            ?.let { name ->
                val source = name.substring(1, name.lastIndex)
                val separatorIndex = source.indexOf('>')

                source
                    .takeIf { separatorIndex > 0 && separatorIndex == source.lastIndexOf('>') }
                    ?.let {
                        GlobalEventPluginBinding(
                            source = name,
                            target = source.substring(0, separatorIndex),
                            event = source.substring(separatorIndex + 1),
                        )
                    }
            }?.takeIf { binding ->
                binding.target.matches(GLOBAL_TARGET) &&
                    binding.event.matches(GLOBAL_EVENT)
            }

    fun isValid(attributeName: String): Boolean = parse(attributeName) != null

    private val GLOBAL_TARGET =
        Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*(?:\\.[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)*")
    private val GLOBAL_EVENT =
        Regex("[A-Za-z0-9_${'$'}:-]+(?:\\.[A-Za-z0-9_${'$'}~:-]+)*")
}

internal data class EventPluginModifier(
    val source: String,
    val description: String,
    val behavior: String,
) {
    companion object {
        fun parse(source: String): EventPluginModifier? =
            when (source) {
                "capture" ->
                    EventPluginModifier(
                        source = source,
                        description = "Listens for the event during the capture phase instead of the bubbling phase.",
                        behavior = "listens during the capture phase",
                    )

                "once" ->
                    EventPluginModifier(
                        source = source,
                        description = "Removes the event listener automatically after the first invocation.",
                        behavior = "removes the listener after the first invocation",
                    )

                "passive" ->
                    EventPluginModifier(
                        source = source,
                        description =
                            "Registers a passive event listener, " +
                                "allowing the browser to optimize input handling.",
                        behavior = "registers the listener as passive",
                    )

                "prevent" ->
                    EventPluginModifier(
                        source = source,
                        description = "Calls event.preventDefault() before invoking the event handler.",
                        behavior = "calls event.preventDefault() before the handler",
                    )

                "self" ->
                    EventPluginModifier(
                        source = source,
                        description = "Invokes the handler only when the event originated from the element itself.",
                        behavior = "ignores bubbled events from descendants",
                    )

                "silent" ->
                    EventPluginModifier(
                        source = source,
                        description = "Legacy alias for zoneless; runs the handler outside Angular's NgZone.",
                        behavior = "runs the handler outside Angular's NgZone",
                    )

                "zoneless" ->
                    EventPluginModifier(
                        source = source,
                        description = "Runs the event handler outside Angular's NgZone to avoid change detection.",
                        behavior = "runs the handler outside Angular's NgZone",
                    )

                "stop" ->
                    EventPluginModifier(
                        source = source,
                        description = "Calls event.stopPropagation() before invoking the event handler.",
                        behavior = "calls event.stopPropagation() before the handler",
                    )

                else -> parseTimedModifier(source)
            }

        private fun parseTimedModifier(source: String): EventPluginModifier? {
            val match = TIMED_MODIFIER.matchEntire(source) ?: return null
            val kind = match.groupValues[1]
            val delay = match.groupValues[2]

            return if (kind == "debounce") {
                EventPluginModifier(
                    source = source,
                    description = "Invokes the handler after events stop arriving for $delay.",
                    behavior = "debounces the handler by $delay",
                )
            } else {
                EventPluginModifier(
                    source = source,
                    description = "Invokes the handler at most once per $delay interval.",
                    behavior = "throttles the handler to once per $delay",
                )
            }
        }

        private val TIMED_MODIFIER = Regex("(debounce|throttle)~(\\d+(?:ms|s))")
    }
}
