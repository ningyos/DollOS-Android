package org.dollos.service.action

import android.util.Log

class ActionRegistry {

    companion object {
        private const val TAG = "ActionRegistry"
    }

    private val actions = mutableMapOf<String, Action>()

    fun register(action: Action) {
        actions[action.id] = action
        Log.i(TAG, "Registered action: ${action.id}")
    }

    fun get(id: String): Action? = actions[id]

    fun getAll(): List<Action> = actions.values.toList()

    fun toToolDescriptions(): String {
        val tools = actions.values.map { action ->
            """{"id":"${action.id}","name":"${action.name}","description":"${action.description}","parameters":${action.parametersSchema},"confirmRequired":${action.confirmRequired}}"""
        }
        return "[${tools.joinToString(",")}]"
    }
}
