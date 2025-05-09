package com.temiremotevisits.adapters

import android.annotation.SuppressLint
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.temiremotevisits.databinding.ItemAppointmentBinding

//all appointments
class AppointmentsRecyclerViewAdapter(private val listener:Listener) : RecyclerView.Adapter<AppointmentsRecyclerViewAdapter.AppointmentsRecyclerViewHolder>() {

    private var slotsList:List<Pair<String,Pair<String, String>>>?=null
    private val TAG = "AppointmentsRecyclerViewAdapter"

    @SuppressLint("NotifyDataSetChanged")
    fun updateList(list:List<Pair<String,Pair<String, String>>>){
        this.slotsList = list
            .sortedWith(compareBy {
                val timeParts = it.second.first.split(":")
                val hours = timeParts[0].toInt()
                val minutes = timeParts[1].toInt()
                hours * 60 + minutes
            })
        Log.d(TAG, this.slotsList.toString())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppointmentsRecyclerViewHolder {
        val binding = ItemAppointmentBinding.inflate(
            LayoutInflater.from(parent.context),parent,false
        )
        return AppointmentsRecyclerViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return slotsList?.size?:0
    }

    override fun onBindViewHolder(holder: AppointmentsRecyclerViewHolder, position: Int) {
        Log.d(TAG, this.slotsList.toString())
        slotsList?.let { list ->
            val user = list[position]
            holder.bind(user, listener)
        }
    }


    interface  Listener {
        fun cancelClicked(date: String, hour: String)
    }

    class AppointmentsRecyclerViewHolder(private val binding: ItemAppointmentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            list: Pair<String, Pair<String, String>>,
            listener: Listener
        ) {
            binding.apply {
                when (list.second.second) {
                    "REQUESTED", "ACCEPTED", "AVAILABLE", "PASSED" -> {
                        statusTV.text = list.second.second
                        hourTV.text = list.second.first

                        cancelButton.text = "Cancelar"
                        cancelButton.setOnClickListener {
                            val builder = AlertDialog.Builder(itemView.context)
                            builder.setMessage("Tem a certeza que pertende cancelar?")
                                .setCancelable(false)
                                .setPositiveButton("Sim") { _, _ ->
                                    listener.cancelClicked(list.first, list.second.first)
                                }
                                .setNegativeButton("Não") { dialog, _ ->
                                    dialog.dismiss()
                                }
                            val alert = builder.create()
                            alert.show()
                        }
                    }
                }
            }
        }
    }
}