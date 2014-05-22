/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.sync.repeats;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import android.content.Intent;

import com.google.ical.values.Frequency;
import com.google.ical.values.RRule;
import com.google.ical.values.Weekday;
import com.google.ical.values.WeekdayNum;
import org.tasks.R;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.api.AstridApiConstants;
import com.todoroo.astrid.dao.MetadataDao;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskDao.TaskCriteria;
import com.todoroo.astrid.data.SyncFlags;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.repeats.RepeatTaskCompleteListener;
import com.todoroo.astrid.test.DatabaseTestCase;
import com.todoroo.astrid.utility.Flags;

abstract public class AbstractSyncRepeatTests<REMOTE_MODEL> extends DatabaseTestCase {

    @Autowired protected TaskDao taskDao;

    @Autowired protected MetadataDao metadataDao;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Preferences.setStringFromInteger(R.string.p_default_urgency_key, 0);
    }

    private void saveAndTriggerRepeatListener(Task task) {
        task.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
        task.putTransitory(SyncFlags.GTASKS_SUPPRESS_SYNC, true);
        if(task.isSaved())
            taskDao.saveExisting(task);
        else
            taskDao.createNew(task);

        Intent intent = new Intent(AstridApiConstants.BROADCAST_EVENT_TASK_COMPLETED);
        intent.putExtra(AstridApiConstants.EXTRAS_TASK_ID, task.getId());
        new RepeatTaskCompleteListener().onReceive(getContext(), intent);
    }

    protected void waitAndSync() {
        // Subclasses can override this to insert sync functionality
    }

    /**
     * @param t
     * @param expectedDueDate
     */
    protected REMOTE_MODEL assertTaskExistsRemotely(Task t, long expectedDueDate) {
        // Subclasses can override this to check the existence of remote objects
        return null;
    }

    /**
     * @param t task
     */
    protected void assertTaskCompletedRemotely(Task t) {
        // Subclasses can override this to check the status of the corresponding remote task
    }


    /**
     * @param remoteModel
     */
    protected long setCompletionDate(boolean completeBefore, Task t,
            REMOTE_MODEL remoteModel, long dueDate) {
        long completionDate = DateUtilities.now();
        t.setCompletionDate(completionDate);
        saveAndTriggerRepeatListener(t);
        return completionDate;
    }

    protected void assertTimesMatch(long expectedTime, long newDueDate) {
        assertTrue(String.format("Expected %s, was %s", new Date(expectedTime), new Date(newDueDate)),
                Math.abs(expectedTime - newDueDate) <= 600000); 
        // Allow a few minutes of variance to account for timing issues in tests
    }

    /*
     * Tests for no sync
     */

    public void testNoRepeat() {
        Task t = new Task();
        t.setTITLE("no repeat");
        taskDao.save(t);

        t.setCompletionDate(DateUtilities.now());
        saveAndTriggerRepeatListener(t);

        TodorooCursor<Task> cursor = taskDao.query(Query.select(Task.ID));
        try {
            assertEquals(1, cursor.getCount());
        } finally {
            cursor.close();
        }
    }

    protected void testRepeating(boolean completeBefore, boolean fromCompletion,
            RRule rrule, Frequency frequency, String title) {
        Task t = new Task();
        t.setTITLE(title);
        long dueDate = DateUtilities.now() + ((completeBefore ? -1 : 1) * DateUtilities.ONE_DAY * 3);
        dueDate = Task.createDueDate(Task.URGENCY_SPECIFIC_DAY_TIME, (dueDate / 1000L) * 1000L); // Strip milliseconds

        t.setDUE_DATE(dueDate);

        if (rrule == null) {
            rrule = new RRule();
            rrule.setFreq(frequency);
            int interval = frequency.equals(Frequency.MINUTELY) ? 100: 2;
            rrule.setInterval(interval);
        }
        
        String result = rrule.toIcal();
        if (fromCompletion)
            result = result + ";FROM=COMPLETION";
        
        t.setRECURRENCE(rrule.toIcal());
        taskDao.save(t);

        waitAndSync();
        t = taskDao.fetch(t.getId(), Task.PROPERTIES); // Refetch
        REMOTE_MODEL remoteModel = assertTaskExistsRemotely(t, dueDate);

        long completionDate = setCompletionDate(completeBefore, t, remoteModel, dueDate);

        waitAndSync();
        assertTaskCompletedRemotely(t);

        TodorooCursor<Task> cursor = taskDao.query(Query.select(Task.PROPERTIES).where(TaskCriteria.notDeleted()));
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                Task task = new Task(cursor);
                System.err.println("Task: " + task.getTITLE() + ", due: " + task.getDUE_DATE());
            }
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            t.readFromCursor(cursor);

            assertEquals(title, t.getTITLE());
            assertFalse(t.isCompleted());
            long newDueDate = t.getDUE_DATE();
            assertTrue(t.hasDueTime());

            long fromDate = (fromCompletion? completionDate : dueDate);
            long expectedTime = computeNextDueDateFromDate(fromDate, rrule, fromCompletion);

            assertTaskExistsRemotely(t, expectedTime);
            assertTimesMatch(expectedTime, newDueDate);
        } finally {
            cursor.close();
        }
    }

    private long computeWeeklyCaseDueDate(long fromDate, RRule rrule, boolean fromCompletion) {
        long result = fromDate;
        Frequency frequency = rrule.getFreq();
        assertTrue(frequency.equals(Frequency.WEEKLY));
        List<WeekdayNum> weekdayNums = rrule.getByDay();

        if (weekdayNums.size() == 0) {
            result += DateUtilities.ONE_WEEK * rrule.getInterval();
            return result;
        }
        HashSet<Weekday> weekdays = new HashSet<Weekday>();
        for (WeekdayNum curr : weekdayNums) {
            weekdays.add(curr.wday);
        }

        Weekday[] allWeekdays = Weekday.values();
//        result -= DateUtilities.ONE_DAY;
        Date date = new Date(result);
        Weekday start = allWeekdays[date.getDay()];
        int i;
        for (i = 0; i < allWeekdays.length; i++) {
            if (start == allWeekdays[i]) break;
        }
        int index = i;
        int daysToAdd = 0;
        Weekday next = null;
        for (i = index + 1; i < allWeekdays.length; i++) {
            Weekday curr = allWeekdays[i];
            daysToAdd++;
            if (weekdays.contains(curr)) {
                next = curr;
                break;
            }
        }

        if (next == null) {
            for (i = 0; i < index + 1; i++) {
                Weekday curr = allWeekdays[i];
                daysToAdd++;
                if (weekdays.contains(curr)) {
                    next = curr;
                    break;
                }
            }
        }

        if (fromCompletion) {
            result += DateUtilities.ONE_WEEK * (rrule.getInterval() - 1);
        }
        result += DateUtilities.ONE_DAY * daysToAdd;
        return result;
    }


    /** Advanced weekly repeating tests */
    protected long computeNextDueDateFromDate(long fromDate, RRule rrule, boolean fromCompletion) {
        long expectedTime = fromDate;
        Frequency frequency = rrule.getFreq();
        int interval = rrule.getInterval();
        if (frequency.equals(Frequency.MINUTELY)) {
            expectedTime += DateUtilities.ONE_MINUTE * interval;
        } else if (frequency.equals(Frequency.HOURLY)) {
            expectedTime += DateUtilities.ONE_HOUR * interval;
        } else if (frequency.equals(Frequency.DAILY)) {
            expectedTime += DateUtilities.ONE_DAY * interval;
        } else if (frequency.equals(Frequency.WEEKLY)) {
            expectedTime = computeWeeklyCaseDueDate(fromDate, rrule, fromCompletion);
        } else if (frequency.equals(Frequency.MONTHLY)) {
            Date originalDate = new Date(expectedTime);
            for (int i = 0; i < interval; i++) {
                int month = originalDate.getMonth();
                if (month == 11) { // Roll over the year and set the month to January
                    originalDate.setYear(originalDate.getYear() + 1);
                    originalDate.setMonth(0);
                } else {
                    originalDate.setMonth(originalDate.getMonth() + 1);
                }
            }
            expectedTime = originalDate.getTime();
        } else if (frequency.equals(Frequency.YEARLY)) {
            Date originalCompleteDate = new Date(expectedTime);
            originalCompleteDate.setYear(originalCompleteDate.getYear() + interval);
            expectedTime = originalCompleteDate.getTime();
        }
        
        return Task.createDueDate(Task.URGENCY_SPECIFIC_DAY_TIME, expectedTime);
    }

    private void testFromDueDate(boolean completeBefore, Frequency frequency, String title) {
        testRepeating(completeBefore, false, null, frequency, title);
    }

    private void testFromCompletionDate(boolean completeBefore, Frequency frequency, String title) {
        testRepeating(completeBefore, true, null, frequency, title);
    }


    /** Tests for repeating from due date */

    public void testRepeatMinutelyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.MINUTELY, "minutely-before");
    }

    public void testRepeatMinutelyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.MINUTELY, "minutely-after");
    }

    public void testRepeatHourlyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.HOURLY, "hourly-before");
    }

    public void testRepeatHourlyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.HOURLY, "hourly-after");
    }

    public void testRepeatDailyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.DAILY, "daily-before");
    }

    public void testRepeatDailyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.DAILY, "daily-after");
    }

    public void testRepeatWeeklyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.WEEKLY, "weekly-before");
    }

    public void testRepeatWeeklyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.WEEKLY, "weekly-after");
    }

    public void testRepeatMonthlyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.MONTHLY, "monthly-before");
    }

    public void testRepeatMonthlyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.MONTHLY, "monthly-after");
    }

    public void testRepeatYearlyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.YEARLY, "yearly-before");
    }

    public void testRepeatYearlyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.YEARLY, "yearly-after");
    }


    /** Tests for repeating from completionDate */

    public void testRepeatMinutelyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.MINUTELY, "minutely-before");
    }

    public void testRepeatMinutelyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.MINUTELY, "minutely-after");
    }

    public void testRepeatHourlyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.HOURLY, "hourly-before");
    }

    public void testRepeatHourlyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.HOURLY, "hourly-after");
    }

    public void testRepeatDailyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.DAILY, "daily-before");
    }

    public void testRepeatDailyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.DAILY, "daily-after");
    }

    public void testRepeatWeeklyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.WEEKLY, "weekly-before");
    }

    public void testRepeatWeeklyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.WEEKLY, "weekly-after");
    }

    public void testRepeatMonthlyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.MONTHLY, "monthly-before");
    }

    public void testRepeatMonthlyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.MONTHLY, "monthly-after");
    }

    public void testRepeatYearlyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.YEARLY, "yearly-before");
    }

    public void testRepeatYearlyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.YEARLY, "yearly-after");
    }

    private void testAdvancedWeekly(boolean fromCompletion, boolean completeBefore, String title) {
        RRule rrule = new RRule();
        rrule.setFreq(Frequency.WEEKLY);

        int interval = 1;
        rrule.setInterval(interval);
        List<WeekdayNum> weekdays = new ArrayList<WeekdayNum>();
        weekdays.add(new WeekdayNum(0, Weekday.MO));
        weekdays.add(new WeekdayNum(0, Weekday.WE));
        rrule.setByDay(weekdays);
        testRepeating(completeBefore, fromCompletion, rrule, Frequency.WEEKLY, title);
    }



    // disabled until test can be fixed
    public void testAdvancedRepeatWeeklyFromDueDateCompleteBefore() {
        testAdvancedWeekly(false, true, "advanced-weekly-before");
    }

    public void testAdvancedRepeatWeeklyFromDueDateCompleteAfter() {
        testAdvancedWeekly(false, false, "advanced-weekly-after");
    }

    public void testAdvancedRepeatWeeklyFromCompleteDateCompleteBefore() {
        testAdvancedWeekly(true, true, "advanced-weekly-before");
    }

    public void testAdvancedRepeatWeeklyFromCompleteDateCompleteAfter() {
        testAdvancedWeekly(true, false, "advanced-weekly-after");
    }
}
