package com.temiremotevisits.utils

import java.io.Serializable

data class Appointment(
    val id: String,
    val date: String,
    val hour: String,
    val status: String,
    val time: String,
    val location: String,
    val userEmail: String
) : Serializable