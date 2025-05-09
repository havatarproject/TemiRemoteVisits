package com.temiremotevisits.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.temiremotevisits.R
import com.temiremotevisits.databinding.ItemCallRecyclerViewBinding

class CallsRecyclerViewAdapter(private val listener:Listener) : RecyclerView.Adapter<CallsRecyclerViewAdapter.CallsRecyclerViewHolder>()  {
    private var usersList:List<Pair<String,String>>?=null
    fun updateList(list:List<Pair<String,String>>){
        this.usersList = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallsRecyclerViewHolder {
        val binding = ItemCallRecyclerViewBinding.inflate(
            LayoutInflater.from(parent.context),parent,false
        )
        return CallsRecyclerViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return usersList?.size?:0
    }

    override fun onBindViewHolder(holder: CallsRecyclerViewHolder, position: Int) {
        usersList?.let { list->
            val user = list[position]
            holder.bind(user,{
                listener.onVideoCallClicked(it)
            },{
                listener.onAudioCallClicked(it)
            })
        }
    }

    interface  Listener {
        fun onVideoCallClicked(username:String)
        fun onAudioCallClicked(username:String)
    }

    class CallsRecyclerViewHolder(private val binding: ItemCallRecyclerViewBinding):
        RecyclerView.ViewHolder(binding.root){
        private val context = binding.root.context

        fun bind(
            user:Pair<String,String>,
            videoCallClicked:(String) -> Unit,
            audioCallClicked:(String)-> Unit
        ){
            binding.apply {
                when (user.second) {
                    "ONLINE" -> {
                        videoCallBtn.isVisible = true
                        audioCallBtn.isVisible = true
                        videoCallBtn.setOnClickListener {
                            videoCallClicked.invoke(user.first)
                        }
                        audioCallBtn.setOnClickListener {
                            audioCallClicked.invoke(user.first)
                        }
                        statusTv.setTextColor(context.resources.getColor(R.color.faded_black, null))
                        statusTv.text = "Online"
                    }
                    "OFFLINE" -> {
                        videoCallBtn.isVisible = false
                        audioCallBtn.isVisible = false
                        statusTv.setTextColor(context.resources.getColor(R.color.faded_black, null))
                        statusTv.text = "Offline"
                    }
                    "IN_CALL" -> {
                        videoCallBtn.isVisible = false
                        audioCallBtn.isVisible = false
                        statusTv.setTextColor(context.resources.getColor(R.color.faded_black, null))
                        statusTv.text = "In Call"
                    }
                }
                usernameTv.text = user.first
            }
        }
    }



}