package dev.readflow.features.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderMenuMotionPolicyTest {

    @Test
    fun `reader menu motion stays subtle and avoids layout animation`() {
        val policy = loadPolicyObject()

        assertEquals(
            150.0,
            policy.number("durationMillis").toDouble(),
            0.0,
            "durationMillis must be exactly 150",
        )

        val panelTranslationDp = policy.number("panelTranslationDp").toDouble()
        assertTrue(
            panelTranslationDp in 8.0..12.0,
            "panelTranslationDp must stay between 8dp and 12dp, but was $panelTranslationDp",
        )

        assertFalse(policy.boolean("animatesLayoutSize"), "animatesLayoutSize must be false")
        assertFalse(policy.boolean("usesSpring"), "usesSpring must be false")
        assertTrue(policy.boolean("animatesAlpha"), "animatesAlpha must be true")
        assertTrue(policy.boolean("animatesTranslation"), "animatesTranslation must be true")
    }

    private fun loadPolicyObject(): ReflectedPolicy {
        val type = try {
            Class.forName(POLICY_CLASS_NAME)
        } catch (error: ClassNotFoundException) {
            throw AssertionError(
                "Expected reader menu motion policy object $POLICY_CLASS_NAME to exist",
                error,
            )
        }

        val instance = try {
            type.getField("INSTANCE").get(null)
        } catch (error: ReflectiveOperationException) {
            throw AssertionError(
                "Expected $POLICY_CLASS_NAME to be a Kotlin object with a public INSTANCE",
                error,
            )
        }

        return ReflectedPolicy(type, instance)
    }

    private class ReflectedPolicy(
        private val type: Class<*>,
        private val instance: Any,
    ) {
        fun number(name: String): Number = property(name) as? Number
            ?: throw AssertionError("Expected $POLICY_CLASS_NAME.$name to be numeric")

        fun boolean(name: String): Boolean = property(name) as? Boolean
            ?: throw AssertionError("Expected $POLICY_CLASS_NAME.$name to be Boolean")

        private fun property(name: String): Any {
            val suffix = name.replaceFirstChar { it.uppercase() }
            val getter = type.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "get$suffix" || it.name == "is$suffix")
            }

            val value = try {
                getter?.invoke(instance) ?: type.getField(name).get(instance)
            } catch (error: ReflectiveOperationException) {
                throw AssertionError(
                    "Expected $POLICY_CLASS_NAME to expose public property $name",
                    error,
                )
            }

            return value
                ?: throw AssertionError("Expected $POLICY_CLASS_NAME.$name to be non-null")
        }
    }

    private companion object {
        const val POLICY_CLASS_NAME = "dev.readflow.features.reader.ReaderMenuMotionPolicy"
    }
}
