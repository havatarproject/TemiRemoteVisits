package com.temiremotevisits.utils

data class AppointmentData(
    var id: String? = null,
    var userEmail: String? = null,
    val note: String? = null,
    val date: String? = null,
    val hour: String? = null,
    val time: String? = null, //hour + time - enddate
    var status: Status? = Status.AVAILABLE,
    var bed: String? = null
)