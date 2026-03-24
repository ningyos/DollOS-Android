package org.dollos.service.action

import android.content.Context
import org.json.JSONObject

data class ActionResult(
    val success: Boolean,
    val message: String
)

interface Action {
    val id: String
    val name: String
    val description: String
    val parametersSchema: String
    val confirmRequired: Boolean

    fun execute(context: Context, params: JSONObject): ActionResult
}
