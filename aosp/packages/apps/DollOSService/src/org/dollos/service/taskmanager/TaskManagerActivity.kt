package org.dollos.service.taskmanager

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.dollos.service.R
import android.view.LayoutInflater
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskManagerActivity : Activity() {

    private lateinit var taskList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var statusText: TextView
    private val tasks = mutableListOf<AITask>()
    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen, like power menu
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR)

        setContentView(R.layout.activity_task_manager)

        // Tap on dark background to dismiss
        findViewById<View>(R.id.background_overlay).setOnClickListener { finish() }

        taskList = findViewById(R.id.task_list)
        emptyText = findViewById(R.id.empty_text)
        statusText = findViewById(R.id.status_text)

        adapter = TaskAdapter(tasks) { task -> cancelTask(task) }
        taskList.layoutManager = LinearLayoutManager(this)
        taskList.adapter = adapter

        findViewById<TextView>(R.id.btn_resume).setOnClickListener {
            resumeAndFinish()
        }

        loadTasks()
    }

    private fun loadTasks() {
        // TODO: In future, get tasks from DollOSAIService via Binder
        // For now, show empty state -- AIService doesn't exist yet
        val tasksJson = intent.getStringExtra("tasks_json") ?: "[]"
        tasks.clear()
        tasks.addAll(AITask.listFromJson(tasksJson))

        if (tasks.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            taskList.visibility = View.GONE
            statusText.text = "No active AI tasks"
        } else {
            emptyText.visibility = View.GONE
            taskList.visibility = View.VISIBLE
            statusText.text = "${tasks.size} task(s) paused"
        }
        adapter.notifyDataSetChanged()
    }

    private fun cancelTask(task: AITask) {
        tasks.remove(task)
        adapter.notifyDataSetChanged()
        // TODO: Send cancel signal to DollOSAIService
        if (tasks.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            taskList.visibility = View.GONE
            statusText.text = "All tasks cancelled"
        }
    }

    private fun resumeAndFinish() {
        // TODO: Send resume signal to DollOSAIService
        finish()
    }

    private inner class TaskAdapter(
        private val tasks: List<AITask>,
        private val onCancel: (AITask) -> Unit
    ) : RecyclerView.Adapter<TaskViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ai_task, parent, false)
            return TaskViewHolder(view)
        }

        override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
            holder.bind(tasks[position], onCancel)
        }

        override fun getItemCount() = tasks.size
    }

    private class TaskViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val nameText: TextView = view.findViewById(R.id.task_name)
        private val descText: TextView = view.findViewById(R.id.task_description)
        private val timeText: TextView = view.findViewById(R.id.task_time)
        private val tokensText: TextView = view.findViewById(R.id.task_tokens)
        private val cancelBtn: TextView = view.findViewById(R.id.btn_cancel)

        fun bind(task: AITask, onCancel: (AITask) -> Unit) {
            nameText.text = task.name
            descText.text = task.description
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            timeText.text = "Started: ${sdf.format(Date(task.startTime))}"
            tokensText.text = "${task.tokenUsage} tokens (~$${String.format("%.4f", task.estimatedCost)})"
            cancelBtn.setOnClickListener { onCancel(task) }
        }
    }
}
