package com.temiremotevisits.firebaseClient

import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.gson.Gson
import com.temiremotevisits.utils.Appointment
import com.temiremotevisits.utils.DataModel
import com.temiremotevisits.utils.FirebaseFieldNames.ID
import com.temiremotevisits.utils.FirebaseFieldNames.LATEST_EVENT
import com.temiremotevisits.utils.FirebaseFieldNames.STATUS
import com.temiremotevisits.utils.MyEventListener
import com.temiremotevisits.utils.Status
import com.temiremotevisits.utils.UserStatus
import com.temiremotevisits.utils.Utils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseClient @Inject constructor(
    private val dbRef: DatabaseReference,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "FirebaseClient"
    }

    private var currentUsername:String?=null
    private var currentId:String?=null
    fun setUsername(username: String){
        this.currentUsername = username
    }

    private fun setID(id: String){
        this.currentId = id
    }

    fun login(username: String?, id: String?, done: (Boolean, String?) -> Unit) {
        dbRef.addListenerForSingleValueEvent(object  : MyEventListener(){
            override fun onDataChange(snapshot: DataSnapshot) {
                //if the current user exists
                val user = Utils.encodeEmail(username)
                if (snapshot.hasChild(user)){
                    dbRef.child(user).child(STATUS).setValue(UserStatus.ONLINE)
                        .addOnCompleteListener {
                            setUsername(user)
                            done(true,null)
                        }.addOnFailureListener {
                            done(false,"${it.message}")
                        }
                }else{
                    //register the user
                    dbRef.child(user).child(STATUS).setValue(UserStatus.OFFLINE)
                        .addOnCompleteListener { statusSetTask ->
                            if (statusSetTask.isSuccessful) {
                                dbRef.child(user).child(ID).setValue(id)
                                    .addOnCompleteListener { idSetTask ->
                                        if (idSetTask.isSuccessful) {
                                            setUsername(user)
                                            if (id != null) {
                                                setID(id)
                                            }
                                            done(true, null)
                                        } else {
                                            done(false, idSetTask.exception?.message)
                                        }
                                    }.addOnFailureListener { exception ->
                                        done(false, exception.message)
                                    }
                            } else {
                                done(false, statusSetTask.exception?.message)
                            }
                        }.addOnFailureListener { exception ->
                            done(false, exception.message)
                        }

                }
            }
        })
    }
    fun observeUsersStatus(status: (List<Pair<String, String>>) -> Unit) {
        dbRef.addValueEventListener(object : MyEventListener() {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.filter { it.key !=currentUsername }.map {
                    it.key!! to it.child(STATUS).value.toString()
                }
                status(list)
            }
        })
    }

    fun subscribeForLatestEvent(listener: Listener){
        try {
            currentUsername?.let { Log.d("subscribeForLatestEvent", it) }
            Log.d("subscribeForLatestEvent", Utils.encodeEmail(FirebaseAuth.getInstance().currentUser?.email.toString()))
            dbRef.child(Utils.encodeEmail(FirebaseAuth.getInstance().currentUser?.email.toString())).child(LATEST_EVENT).addValueEventListener(
                object : MyEventListener() {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        super.onDataChange(snapshot)
                        val event = try {
                            gson.fromJson(snapshot.value.toString(), DataModel::class.java)
                        }catch (e:Exception){
                            e.printStackTrace()
                            null
                        }
                        event?.let {
                            listener.onLatestEventReceived(it)
                        }
                    }
                }
            )
        }catch (e:Exception){
            e.printStackTrace()
        }
    }

    fun sendMessageToOtherClient(message: DataModel, success:(Boolean) -> Unit){
        val convertedMessage = gson.toJson(message.copy(sender = currentUsername))
        dbRef.child(message.target).child(LATEST_EVENT).setValue(convertedMessage)
            .addOnCompleteListener {
                success(true)
            }.addOnFailureListener {
                success(false)
            }
    }

    fun changeMyStatus(status: UserStatus) {
        setUsername(Utils.encodeEmail(FirebaseAuth.getInstance().currentUser?.email.toString()))
        dbRef.child(currentUsername!!).child(STATUS).setValue(status.name)
    }

    fun clearLatestEvent() {
        dbRef.child(currentUsername!!).child(LATEST_EVENT).setValue(null)
    }

    fun logOff(function:()->Unit) {
        dbRef.child(currentUsername!!).child(STATUS).setValue(UserStatus.OFFLINE)
            .addOnCompleteListener { function() }
    }

    fun observeAppointments(selectedDate : Date, callback: (List<Pair<String, Pair<String, String>>>) -> Unit): ListenerRegistration {
        val db = FirebaseFirestore.getInstance()
        val appointmentsRef = db.collection("appointmentSlots")
        Log.d("selectedDate", selectedDate.toString())
        val calendar = Calendar.getInstance()
        calendar.time = selectedDate
        selectedDate.let {
            val timeCalendar = Calendar.getInstance()
            timeCalendar.time = it
            calendar.set(Calendar.HOUR_OF_DAY, timeCalendar.get(Calendar.HOUR_OF_DAY))
            calendar.set(Calendar.MINUTE, timeCalendar.get(Calendar.MINUTE))
        }
        val formattedSelectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(
            selectedDate //TODO not the best way to change the appointments passed status
        )

        updatePassedSlots(formattedSelectedDate)
        Log.d("formattedDate", formattedSelectedDate)
        return appointmentsRef.whereEqualTo("date", formattedSelectedDate).addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w("observeAppointments", "Listen failed.", e)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                Log.d("Snapshot", snapshot.documents.toString())
                val appointmentList = snapshot.documents.mapNotNull { document ->
                    val day = document.getString("date") ?: ""
                    val hour = document.getString("hour") ?: ""
                    val status = document.getString("status") ?: ""

                    if (day.isNotEmpty() && hour.isNotEmpty() && status.isNotEmpty()) {
                        Pair(day, Pair(hour, status))
                    } else {
                        null
                    }
                }
                Log.d("EventAppointList", appointmentList.toString())
                callback(appointmentList)
            }
        }
    }

    fun observeNextConfirmedAppointment(callback: (Appointment?) -> Unit): ListenerRegistration {
        val db = FirebaseFirestore.getInstance()
        val appointmentsRef = db.collection("appointmentSlots")
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)

        return appointmentsRef
            .whereGreaterThanOrEqualTo("date", currentDate)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("observeNextConfirmedAppointment", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val appointmentList = snapshot.documents.mapNotNull { document ->
                        val id = document.getString("id") ?: ""
                        val date = document.getString("date") ?: ""
                        val hour = document.getString("hour") ?: ""
                        val status = document.getString("status") ?: ""
                        val time = document.getString("time") ?: ""
                        val location = document.getString("location") ?: ""
                        val userEmail = document.getString("userEmail") ?: ""

                        if (date.isNotEmpty() && hour.isNotEmpty() && status == Status.ACCEPTED.toString()) {
                            Appointment(id, date, hour, status, time, location, userEmail)
                        } else {
                            null
                        }
                    }.sortedWith(compareBy(
                        { it.date },
                        { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).parse(it.hour) }
                    ))


                    val nextConfirmedAppointment = appointmentList.firstOrNull { appointment ->
                        val appointmentDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .parse("${appointment.date} ${appointment.hour}")
                        Log.d(TAG, "Appointment/Now")
                        if (appointmentDateTime != null) {
                            Log.d(TAG, appointmentDateTime.toString())
                        }
                        Log.d(TAG, Calendar.getInstance().time.toString())
                        appointmentDateTime?.after(Calendar.getInstance().time) == true
                    }

                    callback(nextConfirmedAppointment)
                }
            }
    }


    private fun updatePassedSlots(selectedDate: String) {
        val db = FirebaseFirestore.getInstance()
        val appointmentsRef = db.collection("appointmentSlots")

        val currentTime = Date(System.currentTimeMillis())
        val currentDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(currentTime)
        val currentTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(currentTime)

        appointmentsRef
            .whereEqualTo("status", Status.AVAILABLE.status)
            .whereEqualTo("date", selectedDate)
            .get()
            .addOnSuccessListener { querySnapshot ->
                val batch = db.batch()
                querySnapshot.documents.forEach { document ->
                    val hourStr = document.getString("hour") ?: ""
                    val dateStr = document.getString("date") ?: ""

                    if (currentDateStr > dateStr || (currentDateStr == dateStr && hourStr < currentTimeStr)) {
                        batch.update(document.reference, "status", Status.PASSED.status)
                        println("Slots updated successfully")
                    }
                }
                batch.commit()
                    .addOnSuccessListener {
                        println("Slots updated successfully")
                    }
                    .addOnFailureListener { e ->
                        println("Error updating slots: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                println("Error fetching slots: ${e.message}")
            }
    }

    fun sendLocations(locations: List<String>?) {
        val db = FirebaseFirestore.getInstance()
        val locationsRef = db.collection("locations")

        if (locations.isNullOrEmpty()) {
            Log.d(TAG, "Locations list is null or empty. No changes will be made.")
            return
        }

        Log.d(TAG, "Updating locations: ${locations}")

        locationsRef.get()
            .addOnSuccessListener { querySnapshot ->
                val existingLocations = querySnapshot.documents.mapNotNull { it.id to it.getString("location") }.toMap()
                val newLocationsSet = locations.toSet()
                val existingLocationsSet = existingLocations.values.toSet()

                val locationsToRemove = existingLocationsSet - newLocationsSet
                val locationsToAdd = newLocationsSet - existingLocationsSet

                val batch = db.batch()

                for ((id, location) in existingLocations) {
                    if (location in locationsToRemove) {
                        batch.delete(locationsRef.document(id))
                    }
                }

                for (location in locationsToAdd) {
                    val data = mapOf("location" to location)
                    batch.set(locationsRef.document(), data)
                }

                if (locationsToRemove.isNotEmpty() || locationsToAdd.isNotEmpty()) {
                    batch.commit()
                        .addOnSuccessListener {
                            Log.d(TAG, "Locations synced successfully")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Error syncing locations: ${e.message}")
                        }
                } else {
                    Log.d(TAG, "No changes needed for locations")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching locations: ${e.message}")
            }
    }
    /*
    fun loadExistingSlots(date: String) {
        val db = FirebaseFirestore.getInstance()
        val formattedSelectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        db.collection("appointmentSlots")
            .whereEqualTo("date", formattedSelectedDate)
            .get()
            .addOnSuccessListener { documents ->
                slotsList.clear()
                for (document in documents) {
                    val appointment = document.toObject(AppointmentData::class.java)
                    appointment.id = document.id

                    // Check if appointment is passed and update if necessary
                    val slotTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).parse("${appointment.date} ${appointment.hour}")
                    if (slotTime != null && slotTime.before(Calendar.getInstance().time) && appointment.status != Status.PASSED) {
                        appointment.status = Status.PASSED
                        firestore.collection("appointmentSlots").document(document.id).set(appointment)
                    }
                    slotsList.add(appointment)
                }
                updateSlotsListView()
            }
    }
    */

    fun cancelReserve(date: String, hour: String) {
        //TODO does nothing
    }

    interface Listener {
        fun onLatestEventReceived(event: DataModel)
    }
}