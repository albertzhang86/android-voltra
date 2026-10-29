package io.github.albertzhang86.voltra

/** Overall setup intent for displays. Never changes readiness or authorizes a device command. */
enum class SetupActivity(val wireValue: Int) {
    CONNECTING(1), CREATE_TWIN(2), EXIT_TWIN(3), CONFIGURE(4), LOAD(5);

    companion object {
        fun fromWire(value: Int): SetupActivity? = entries.firstOrNull { it.wireValue == value }
    }
}
