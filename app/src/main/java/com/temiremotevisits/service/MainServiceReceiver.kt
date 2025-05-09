package com.temiremotevisits.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.temiremotevisits.CloseActivity
import javax.inject.Inject

class MainServiceReceiver : BroadcastReceiver() {

    @Inject
    lateinit var serviceRepository: MainServiceRepository
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "ACTION_EXIT"){
            serviceRepository.stopService()
            context?.startActivity(Intent(context, CloseActivity::class.java))
        }
    }
}