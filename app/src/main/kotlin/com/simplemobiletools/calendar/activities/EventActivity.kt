package com.simplemobiletools.calendar.activities

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.dialogs.DeleteEventDialog
import com.simplemobiletools.calendar.dialogs.RepeatRuleDailyDialog
import com.simplemobiletools.calendar.dialogs.SelectEventTypeDialog
import com.simplemobiletools.calendar.extensions.*
import com.simplemobiletools.calendar.helpers.*
import com.simplemobiletools.calendar.helpers.Formatter
import com.simplemobiletools.calendar.models.Event
import com.simplemobiletools.commons.extensions.*
import kotlinx.android.synthetic.main.activity_event.*
import org.joda.time.DateTime
import java.util.*

class EventActivity : SimpleActivity(), DBHelper.EventUpdateListener {
    private var mWasEndDateSet = false
    private var mWasEndTimeSet = false
    private var mReminder1Minutes = 0
    private var mReminder2Minutes = 0
    private var mReminder3Minutes = 0
    private var mRepeatInterval = 0
    private var mRepeatLimit = 0
    private var mRepeatRule = 0
    private var mEventTypeId = DBHelper.REGULAR_EVENT_TYPE_ID
    private var mDialogTheme = 0
    private var mEventOccurrenceTS = 0

    lateinit var mEventStartDateTime: DateTime
    lateinit var mEventEndDateTime: DateTime
    lateinit var mEvent: Event

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event)

        val intent = intent ?: return
        mDialogTheme = getAppropriateTheme()

        val eventId = intent.getIntExtra(EVENT_ID, 0)
        val event = dbHelper.getEvent(eventId)
        if (event != null) {
            mEvent = event
            mEventOccurrenceTS = intent.getIntExtra(EVENT_OCCURRENCE_TS, 0)
            setupEditEvent()
        } else {
            mEvent = Event()
            mReminder1Minutes = config.defaultReminderMinutes
            mReminder2Minutes = -1
            mReminder3Minutes = -1
            val startTS = intent.getIntExtra(NEW_EVENT_START_TS, 0)
            if (startTS == 0)
                return

            setupNewEvent(Formatter.getDateTimeFromTS(startTS))
        }

        checkReminderTexts()
        updateRepetitionText()
        updateStartDate()
        updateStartTime()
        updateEndDate()
        updateEndTime()
        updateEventType()

        mWasEndDateSet = event != null
        mWasEndTimeSet = event != null

        event_start_date.setOnClickListener { setupStartDate() }
        event_start_time.setOnClickListener { setupStartTime() }
        event_end_date.setOnClickListener { setupEndDate() }
        event_end_time.setOnClickListener { setupEndTime() }

        event_all_day.setOnCheckedChangeListener { compoundButton, isChecked -> toggleAllDay(isChecked) }
        event_repetition.setOnClickListener { showRepeatIntervalDialog() }
        event_repetition_rule_holder.setOnClickListener { showRepetitionRuleDialog() }
        event_repetition_limit_holder.setOnClickListener { showRepetitionLimitDialog() }

        event_reminder_1.setOnClickListener { showReminder1Dialog() }
        event_reminder_2.setOnClickListener { showReminder2Dialog() }
        event_reminder_3.setOnClickListener { showReminder3Dialog() }

        event_type_holder.setOnClickListener { showEventTypeDialog() }

        if (mEvent.flags and FLAG_ALL_DAY != 0)
            event_all_day.toggle()

        updateTextColors(event_scrollview)
        updateIconColors()
    }

    private fun setupEditEvent() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        title = resources.getString(R.string.edit_event)
        mEventStartDateTime = Formatter.getDateTimeFromTS(mEvent.startTS)
        mEventEndDateTime = Formatter.getDateTimeFromTS(mEvent.endTS)
        event_title.setText(mEvent.title)
        event_description.setText(mEvent.description)
        mReminder1Minutes = mEvent.reminder1Minutes
        mReminder2Minutes = mEvent.reminder2Minutes
        mReminder3Minutes = mEvent.reminder3Minutes
        mRepeatInterval = mEvent.repeatInterval
        mRepeatLimit = mEvent.repeatLimit
        mRepeatRule = mEvent.repeatRule
        mEventTypeId = mEvent.eventType
        checkRepeatTexts(mRepeatInterval)
    }

    private fun setupNewEvent(dateTime: DateTime) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        title = resources.getString(R.string.new_event)
        mEventStartDateTime = dateTime

        val addHours = if (intent.getBooleanExtra(NEW_EVENT_SET_HOUR_DURATION, false)) 1 else 0
        mEventEndDateTime = mEventStartDateTime.plusHours(addHours)
    }

    private fun showReminder1Dialog() {
        showEventReminderDialog(mReminder1Minutes) {
            mReminder1Minutes = it
            checkReminderTexts()
        }
    }

    private fun showReminder2Dialog() {
        showEventReminderDialog(mReminder2Minutes) {
            mReminder2Minutes = it
            checkReminderTexts()
        }
    }

    private fun showReminder3Dialog() {
        showEventReminderDialog(mReminder3Minutes) {
            mReminder3Minutes = it
            checkReminderTexts()
        }
    }

    private fun showRepeatIntervalDialog() {
        showEventRepeatIntervalDialog(mRepeatInterval) {
            setRepeatInterval(it)
        }
    }

    private fun setRepeatInterval(interval: Int) {
        mRepeatInterval = interval
        updateRepetitionText()
        checkRepeatTexts(interval)

        if (isXWeeklyRepetition()) {
            setRepeatRule(Math.pow(2.0, (mEventStartDateTime.dayOfWeek - 1).toDouble()).toInt())
        }
    }

    private fun checkRepeatTexts(limit: Int) {
        event_repetition_limit_holder.beGoneIf(limit == 0)
        checkRepetitionLimitText()

        event_repetition_rule_holder.beVisibleIf(isXWeeklyRepetition())
        checkRepetitionRuleText()
    }

    @SuppressLint("NewApi")
    private fun showRepetitionLimitDialog() {
        hideKeyboard()
        val now = (System.currentTimeMillis() / 1000).toInt()
        val repeatLimitDateTime = Formatter.getDateTimeFromTS(if (mRepeatLimit != 0) mRepeatLimit else now)
        val datepicker = DatePickerDialog(this, mDialogTheme, repetitionLimitDateSetListener, repeatLimitDateTime.year, repeatLimitDateTime.monthOfYear - 1,
                repeatLimitDateTime.dayOfMonth)

        if (isLollipopPlus()) {
            datepicker.datePicker.firstDayOfWeek = if (config.isSundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        }

        datepicker.show()
    }

    private val repetitionLimitDateSetListener = DatePickerDialog.OnDateSetListener { view, year, monthOfYear, dayOfMonth ->
        val repeatLimitDateTime = DateTime().withDate(year, monthOfYear + 1, dayOfMonth).withTime(23, 59, 59, 0)
        if (repeatLimitDateTime.seconds() < mEvent.endTS) {
            mRepeatLimit = 0
        } else {
            mRepeatLimit = repeatLimitDateTime.seconds()
        }
        checkRepetitionLimitText()
    }

    private fun checkRepetitionLimitText() {
        event_repetition_limit.text = if (mRepeatLimit == 0) {
            resources.getString(R.string.forever)
        } else {
            val repeatLimitDateTime = Formatter.getDateTimeFromTS(mRepeatLimit)
            Formatter.getDate(applicationContext, repeatLimitDateTime, false)
        }
    }

    private fun showRepetitionRuleDialog() {
        hideKeyboard()
        if (isXWeeklyRepetition()) {
            RepeatRuleDailyDialog(this, mRepeatRule) {
                setRepeatRule(it)
            }
        }
    }

    private fun setRepeatRule(rule: Int) {
        mRepeatRule = rule
        checkRepetitionRuleText()
        if (rule == 0) {
            setRepeatInterval(0)
        }
    }

    private fun checkRepetitionRuleText() {
        if (isXWeeklyRepetition()) {
            event_repetition_rule.text = if (mRepeatRule == EVERY_DAY) getString(R.string.every_day) else getSelectedDaysString()
        }
    }

    private fun getSelectedDaysString(): String {
        var days = ""
        if (mRepeatRule and MONDAY != 0)
            days += "${getString(R.string.monday).substringTo(3)}, "
        if (mRepeatRule and TUESDAY != 0)
            days += "${getString(R.string.tuesday).substringTo(3)}, "
        if (mRepeatRule and WEDNESDAY != 0)
            days += "${getString(R.string.wednesday).substringTo(3)}, "
        if (mRepeatRule and THURSDAY != 0)
            days += "${getString(R.string.thursday).substringTo(3)}, "
        if (mRepeatRule and FRIDAY != 0)
            days += "${getString(R.string.friday).substringTo(3)}, "
        if (mRepeatRule and SATURDAY != 0)
            days += "${getString(R.string.saturday).substringTo(3)}, "
        if (mRepeatRule and SUNDAY != 0)
            days += "${getString(R.string.sunday).substringTo(3)}, "

        return days.trim().trimEnd(',')
    }

    private fun showEventTypeDialog() {
        hideKeyboard()
        SelectEventTypeDialog(this, mEventTypeId) {
            mEventTypeId = it
            updateEventType()
        }
    }

    private fun checkReminderTexts() {
        updateReminder1Text()
        updateReminder2Text()
        updateReminder3Text()
    }

    private fun updateReminder1Text() {
        event_reminder_1.text = getReminderText(mReminder1Minutes)
        if (mReminder1Minutes == REMINDER_OFF) {
            mReminder2Minutes = REMINDER_OFF
            mReminder3Minutes = REMINDER_OFF
        }
    }

    private fun updateReminder2Text() {
        event_reminder_2.apply {
            beGoneIf(mReminder1Minutes == REMINDER_OFF)
            if (mReminder2Minutes == REMINDER_OFF) {
                text = resources.getString(R.string.add_another_reminder)
                alpha = 0.4f
                mReminder3Minutes = REMINDER_OFF
            } else {
                text = getReminderText(mReminder2Minutes)
                alpha = 1f
            }
        }
    }

    private fun updateReminder3Text() {
        event_reminder_3.apply {
            beGoneIf(mReminder2Minutes == REMINDER_OFF || mReminder1Minutes == REMINDER_OFF)
            if (mReminder3Minutes == REMINDER_OFF) {
                text = resources.getString(R.string.add_another_reminder)
                alpha = 0.4f
            } else {
                text = getReminderText(mReminder3Minutes)
                alpha = 1f
            }
        }
    }

    private fun updateRepetitionText() {
        event_repetition.text = getRepetitionText(mRepeatInterval)
    }

    private fun updateEventType() {
        val eventType = dbHelper.getEventType(mEventTypeId)
        if (eventType != null) {
            event_type.text = eventType.title
            event_type_color.setBackgroundWithStroke(eventType.color, config.backgroundColor)
        }
    }

    private fun toggleAllDay(isChecked: Boolean) {
        hideKeyboard()
        event_start_time.beGoneIf(isChecked)
        event_end_time.beGoneIf(isChecked)
    }

    private fun isXWeeklyRepetition() = mRepeatInterval != 0 && mRepeatInterval % WEEK == 0

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_event, menu)
        menu.findItem(R.id.delete).isVisible = mEvent.id != 0
        menu.findItem(R.id.share).isVisible = mEvent.id != 0
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.share -> shareEvent()
            R.id.delete -> deleteEvent()
            R.id.save -> saveEvent()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun shareEvent() {
        shareEvents(arrayListOf(mEvent.id))
    }

    private fun deleteEvent() {
        DeleteEventDialog(this, arrayListOf(mEvent.id)) {
            if (it) {
                dbHelper.deleteEvents(arrayOf(mEvent.id.toString()))
            } else {
                dbHelper.addEventRepeatException(mEvent.id, mEventOccurrenceTS)
            }
            finish()
        }
    }

    private fun saveEvent() {
        val newTitle = event_title.value
        if (newTitle.isEmpty()) {
            toast(R.string.title_empty)
            event_title.requestFocus()
            return
        }

        val newStartTS = mEventStartDateTime.seconds()
        val newEndTS = mEventEndDateTime.seconds()

        if (newStartTS > newEndTS) {
            toast(R.string.end_before_start)
            return
        }

        val reminders = sortedSetOf(mReminder1Minutes, mReminder2Minutes, mReminder3Minutes).filter { it != REMINDER_OFF }
        val dbHelper = DBHelper.newInstance(applicationContext, this)
        val newDescription = event_description.value
        mEvent.apply {
            startTS = newStartTS
            endTS = newEndTS
            title = newTitle
            description = newDescription
            reminder1Minutes = reminders.elementAtOrElse(0) { REMINDER_OFF }
            reminder2Minutes = reminders.elementAtOrElse(1) { REMINDER_OFF }
            reminder3Minutes = reminders.elementAtOrElse(2) { REMINDER_OFF }
            repeatInterval = mRepeatInterval
            flags = if (event_all_day.isChecked) (mEvent.flags or FLAG_ALL_DAY) else (mEvent.flags.removeFlag(FLAG_ALL_DAY))
            repeatLimit = if (repeatInterval == 0) 0 else mRepeatLimit
            repeatRule = mRepeatRule
            eventType = mEventTypeId
        }

        if (mEvent.id == 0) {
            dbHelper.insert(mEvent)
        } else {
            dbHelper.update(mEvent)
        }
    }

    private fun updateStartDate() {
        event_start_date.text = Formatter.getDate(applicationContext, mEventStartDateTime)
    }

    private fun updateStartTime() {
        event_start_time.text = Formatter.getTime(this, mEventStartDateTime)
    }

    private fun updateEndDate() {
        event_end_date.text = Formatter.getDate(applicationContext, mEventEndDateTime)
    }

    private fun updateEndTime() {
        event_end_time.text = Formatter.getTime(this, mEventEndDateTime)
    }

    @SuppressLint("NewApi")
    fun setupStartDate() {
        hideKeyboard()
        config.backgroundColor.getContrastColor()
        val datepicker = DatePickerDialog(this, mDialogTheme, startDateSetListener, mEventStartDateTime.year, mEventStartDateTime.monthOfYear - 1,
                mEventStartDateTime.dayOfMonth)

        if (isLollipopPlus()) {
            datepicker.datePicker.firstDayOfWeek = if (config.isSundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        }

        datepicker.show()
    }

    fun setupStartTime() {
        hideKeyboard()
        TimePickerDialog(this, mDialogTheme, startTimeSetListener, mEventStartDateTime.hourOfDay, mEventStartDateTime.minuteOfHour, config.use24hourFormat).show()
    }

    @SuppressLint("NewApi")
    fun setupEndDate() {
        hideKeyboard()
        val datepicker = DatePickerDialog(this, mDialogTheme, endDateSetListener, mEventEndDateTime.year, mEventEndDateTime.monthOfYear - 1,
                mEventEndDateTime.dayOfMonth)

        if (isLollipopPlus()) {
            datepicker.datePicker.firstDayOfWeek = if (config.isSundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        }

        datepicker.show()
    }

    fun setupEndTime() {
        hideKeyboard()
        TimePickerDialog(this, mDialogTheme, endTimeSetListener, mEventEndDateTime.hourOfDay, mEventEndDateTime.minuteOfHour, config.use24hourFormat).show()
    }

    private val startDateSetListener = DatePickerDialog.OnDateSetListener { view, year, monthOfYear, dayOfMonth ->
        dateSet(year, monthOfYear, dayOfMonth, true)
        if (!mWasEndDateSet) {
            dateSet(year, monthOfYear, dayOfMonth, false)
        }
    }

    private val startTimeSetListener = TimePickerDialog.OnTimeSetListener { view, hourOfDay, minute ->
        timeSet(hourOfDay, minute, true)
        if (!mWasEndTimeSet) {
            timeSet(hourOfDay, minute, false)
        }
    }

    private val endDateSetListener = DatePickerDialog.OnDateSetListener { view, year, monthOfYear, dayOfMonth -> dateSet(year, monthOfYear, dayOfMonth, false) }

    private val endTimeSetListener = TimePickerDialog.OnTimeSetListener { view, hourOfDay, minute -> timeSet(hourOfDay, minute, false) }

    private fun dateSet(year: Int, month: Int, day: Int, isStart: Boolean) {
        if (isStart) {
            mEventStartDateTime = mEventStartDateTime.withDate(year, month + 1, day)
            updateStartDate()
            if (mEventStartDateTime.isAfter(mEventEndDateTime)) {
                mEventEndDateTime = mEventStartDateTime
                updateEndDate()
                updateEndTime()
            }
        } else {
            mEventEndDateTime = mEventEndDateTime.withDate(year, month + 1, day)
            updateEndDate()
            mWasEndDateSet = true
        }
    }

    private fun timeSet(hours: Int, minutes: Int, isStart: Boolean) {
        if (isStart) {
            mEventStartDateTime = mEventStartDateTime.withHourOfDay(hours).withMinuteOfHour(minutes)
            updateStartTime()
            if (mEventStartDateTime.isAfter(mEventEndDateTime)) {
                mEventEndDateTime = mEventStartDateTime
                updateEndTime()
            }
        } else {
            mEventEndDateTime = mEventEndDateTime.withHourOfDay(hours).withMinuteOfHour(minutes)
            updateEndTime()
            mWasEndTimeSet = true
        }
    }

    private fun updateIconColors() {
        event_time_image.setColorFilter(config.textColor, PorterDuff.Mode.SRC_IN)
        event_repetition_image.setColorFilter(config.textColor, PorterDuff.Mode.SRC_IN)
        event_reminder_image.setColorFilter(config.textColor, PorterDuff.Mode.SRC_IN)
        event_type_image.setColorFilter(config.textColor, PorterDuff.Mode.SRC_IN)
    }

    override fun eventInserted(event: Event) {
        if (DateTime.now().isAfter(mEventStartDateTime.millis)) {
            toast(R.string.past_event_added)
        } else {
            toast(R.string.event_added)
        }
        finish()
    }

    override fun eventUpdated(event: Event) {
        toast(R.string.event_updated)
        finish()
    }

    override fun eventsDeleted(cnt: Int) {
    }

    override fun gotEvents(events: MutableList<Event>) {

    }
}
