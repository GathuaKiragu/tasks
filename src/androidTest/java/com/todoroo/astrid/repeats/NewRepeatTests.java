/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.repeats;

import android.content.Intent;
import android.support.test.runner.AndroidJUnit4;

import com.google.ical.values.Frequency;
import com.google.ical.values.RRule;
import com.google.ical.values.Weekday;
import com.google.ical.values.WeekdayNum;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.astrid.api.AstridApiConstants;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskDao.TaskCriteria;
import com.todoroo.astrid.data.SyncFlags;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.test.DatabaseTestCase;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.tasks.R;
import org.tasks.injection.TestComponent;
import org.tasks.preferences.Preferences;
import org.tasks.time.DateTime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javax.inject.Inject;

import static android.support.test.InstrumentationRegistry.getTargetContext;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.tasks.date.DateTimeUtils.newDateTime;

@RunWith(AndroidJUnit4.class)
public class NewRepeatTests extends DatabaseTestCase {

    @Inject TaskDao taskDao;
    @Inject Preferences preferences;

    @Override
    public void setUp() {
        super.setUp();
        preferences.setStringFromInteger(R.string.p_default_urgency_key, 0);
    }

    @Override
    protected void inject(TestComponent component) {
        component.inject(this);
    }

    private void saveAndTriggerRepeatListener(Task task) {
        task.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
        if(task.isSaved())
            taskDao.saveExisting(task);
        else
            taskDao.createNew(task);

        Intent intent = new Intent(AstridApiConstants.BROADCAST_EVENT_TASK_COMPLETED);
        intent.putExtra(AstridApiConstants.EXTRAS_TASK_ID, task.getId());
        new RepeatTaskCompleteListener().onReceive(getTargetContext(), intent);
    }

    protected void waitAndSync() {
        // Subclasses can override this to insert sync functionality
        AndroidUtilities.sleepDeep(200L); // Delay to make sure changes persist
    }

    protected long setCompletionDate(boolean completeBefore, Task t, long dueDate) {
        long completionDate;
        if (completeBefore)
            completionDate = dueDate - DateUtilities.ONE_DAY;
        else
            completionDate = dueDate + DateUtilities.ONE_DAY;
        t.setCompletionDate(completionDate);
        saveAndTriggerRepeatListener(t);
        return completionDate;
    }

    protected void assertTimesMatch(long expectedTime, long newDueDate) {
        assertTrue(String.format("Expected %s, was %s", newDateTime(expectedTime), newDateTime(newDueDate)),
                Math.abs(expectedTime - newDueDate) < 5000);
    }

    protected void assertTimesWithinOneHour(long expectedTime, long newDueDate) {
        assertTrue(String.format("Expected %s, was %s", newDateTime(expectedTime), newDateTime(newDueDate)),
                Math.abs(expectedTime - newDueDate) <= DateUtilities.ONE_HOUR);
    }

    /*
     * Tests for no sync
     */

    @Test
    public void testNoRepeat() {
        Task t = new Task();
        t.setTitle("no repeat");
        taskDao.save(t);

        t.setCompletionDate(DateUtilities.now());
        saveAndTriggerRepeatListener(t);

        assertEquals(1, taskDao.toList(Query.select(Task.ID)).size());
    }

    protected void testRepeating(boolean completeBefore, boolean fromCompletion,
            RRule rrule, Frequency frequency, String title) {
        Task t = new Task();
        t.setTitle(title);
        long dueDate = DateUtilities.now() + DateUtilities.ONE_DAY * 3;
        DateTime adjustDate = newDateTime(dueDate).withSecondOfMinute(1);
        dueDate = adjustDate.getMillis();
        dueDate = (dueDate / 1000L) * 1000L; // Strip milliseconds

        t.setDueDate(dueDate);

        if (rrule == null) {
            rrule = new RRule();
            rrule.setFreq(frequency);
            int interval = 2;
            rrule.setInterval(interval);
        }

        String result = rrule.toIcal();
        if (fromCompletion)
            result = result + ";FROM=COMPLETION";
        t.setRecurrence(result);
        taskDao.save(t);

        waitAndSync();
        t = taskDao.fetch(t.getId(), Task.PROPERTIES); // Refetch

        long completionDate = setCompletionDate(completeBefore, t, dueDate);
        System.err.println("Completion date: " + newDateTime(completionDate));

        waitAndSync();

        List<Task> tasks = taskDao.toList(Query.select(Task.PROPERTIES).where(TaskCriteria.notDeleted()));
        assertEquals(1, tasks.size());
        t = tasks.get(0);
        assertEquals(title, t.getTitle());
        assertFalse(t.isCompleted());
        long newDueDate = t.getDueDate();
        assertTrue(t.hasDueTime());

        long fromDate = (fromCompletion? completionDate : dueDate);
        long expectedTime = computeNextDueDateFromDate(fromDate, rrule, fromCompletion);

        if (frequency == Frequency.WEEKLY) // We do this because DST was making the results be off by an hour
            assertTimesWithinOneHour(expectedTime, newDueDate);
        else
            assertTimesMatch(expectedTime, newDueDate);
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
        HashSet<Weekday> weekdays = new HashSet<>();
        for (WeekdayNum curr : weekdayNums) {
            weekdays.add(curr.wday);
        }

        Weekday[] allWeekdays = Weekday.values();
        DateTime date = newDateTime(result);
        Weekday start = allWeekdays[date.getDayOfWeek()];
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
            DateTime originalDate = newDateTime(expectedTime);
            for (int i = 0; i < interval; i++) {
                int month = originalDate.getMonthOfYear();
                if (month == 11) { // Roll over the year and set the month to January
                    originalDate = originalDate
                            .withYear(originalDate.getYear() + 1)
                            .withMonthOfYear(1);
                } else {
                    originalDate = originalDate.withMonthOfYear(originalDate.getMonthOfYear() + 1);
                }
            }
            expectedTime = originalDate.getMillis();
        } else if (frequency.equals(Frequency.YEARLY)) {
            DateTime originalCompleteDate = newDateTime(expectedTime);
            originalCompleteDate = originalCompleteDate.withYear(originalCompleteDate.getYear() + interval);
            expectedTime = originalCompleteDate.getMillis();
        }
        return expectedTime;
    }

    private void testFromDueDate(boolean completeBefore, Frequency frequency, String title) {
        testRepeating(completeBefore, false, null, frequency, title);
    }

    private void testFromCompletionDate(boolean completeBefore, Frequency frequency, String title) {
        testRepeating(completeBefore, true, null, frequency, title);
    }


    /** Tests for repeating from due date */

    @Ignore
    @Test
    public void testRepeatMinutelyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.MINUTELY, "minutely-before");
    }

    @Ignore
    @Test
    public void testRepeatMinutelyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.MINUTELY, "minutely-after");
    }

    @Ignore
    @Test
    public void testRepeatHourlyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.HOURLY, "hourly-before");
    }

    @Ignore
    @Test
    public void testRepeatHourlyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.HOURLY, "hourly-after");
    }

    @Ignore
    @Test
    public void testRepeatDailyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.DAILY, "daily-before");
    }

    @Ignore
    @Test
    public void testRepeatDailyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.DAILY, "daily-after");
    }

    @Ignore
    @Test
    public void testRepeatWeeklyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.WEEKLY, "weekly-before");
    }

    @Ignore
    @Test
    public void testRepeatWeeklyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.WEEKLY, "weekly-after");
    }

    @Ignore
    @Test
    public void testRepeatMonthlyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.MONTHLY, "monthly-before");
    }

    @Ignore
    @Test
    public void testRepeatMonthlyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.MONTHLY, "monthly-after");
    }

    @Ignore
    @Test
    public void testRepeatYearlyFromDueDateCompleteBefore() {
        testFromDueDate(true, Frequency.YEARLY, "yearly-before");
    }

    @Ignore
    @Test
    public void testRepeatYearlyFromDueDateCompleteAfter() {
        testFromDueDate(false, Frequency.YEARLY, "yearly-after");
    }


    /** Tests for repeating from completionDate */

    @Ignore
    @Test
    public void testRepeatMinutelyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.MINUTELY, "minutely-before");
    }

    @Ignore
    @Test
    public void testRepeatMinutelyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.MINUTELY, "minutely-after");
    }

    @Ignore
    @Test
    public void testRepeatHourlyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.HOURLY, "hourly-before");
    }

    @Ignore
    @Test
    public void testRepeatHourlyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.HOURLY, "hourly-after");
    }

    @Ignore
    @Test
    public void testRepeatDailyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.DAILY, "daily-before");
    }

    @Ignore
    @Test
    public void testRepeatDailyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.DAILY, "daily-after");
    }

    @Ignore
    @Test
    public void testRepeatWeeklyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.WEEKLY, "weekly-before");
    }

    @Ignore
    @Test
    public void testRepeatWeeklyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.WEEKLY, "weekly-after");
    }

    @Ignore
    @Test
    public void testRepeatMonthlyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.MONTHLY, "monthly-before");
    }

    @Ignore
    @Test
    public void testRepeatMonthlyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.MONTHLY, "monthly-after");
    }

    @Ignore
    @Test
    public void testRepeatYearlyFromCompleteDateCompleteBefore() {
        testFromCompletionDate(true, Frequency.YEARLY, "yearly-before");
    }

    @Ignore
    @Test
    public void testRepeatYearlyFromCompleteDateCompleteAfter() {
        testFromCompletionDate(false, Frequency.YEARLY, "yearly-after");
    }

    private void testAdvancedWeeklyFromDueDate(boolean completeBefore, String title) {
        RRule rrule = new RRule();
        rrule.setFreq(Frequency.WEEKLY);

        int interval = 1;
        rrule.setInterval(interval);
        List<WeekdayNum> weekdays = new ArrayList<>();
        weekdays.add(new WeekdayNum(0, Weekday.MO));
        weekdays.add(new WeekdayNum(0, Weekday.WE));
        rrule.setByDay(weekdays);
        testRepeating(completeBefore, false, rrule, Frequency.WEEKLY, title);
    }

    private void testAdvancedWeeklyFromCompleteDate(boolean completeBefore, String title) {
        RRule rrule = new RRule();
        rrule.setFreq(Frequency.WEEKLY);

        int interval = 1;
        rrule.setInterval(interval);
        List<WeekdayNum> weekdays = new ArrayList<>();
        weekdays.add(new WeekdayNum(0, Weekday.MO));
        weekdays.add(new WeekdayNum(0, Weekday.WE));
        rrule.setByDay(weekdays);
        testRepeating(completeBefore, true, rrule, Frequency.WEEKLY, title);
    }



    // disabled until test can be fixed
    @Ignore
    @Test
    public void testAdvancedRepeatWeeklyFromDueDateCompleteBefore() {
        testAdvancedWeeklyFromDueDate(true, "advanced-weekly-before");
    }

    @Ignore
    @Test
    public void testAdvancedRepeatWeeklyFromDueDateCompleteAfter() {
        testAdvancedWeeklyFromDueDate(false, "advanced-weekly-after");
    }

    @Ignore
    @Test
    public void testAdvancedRepeatWeeklyFromCompleteDateCompleteBefore() {
        testAdvancedWeeklyFromCompleteDate(true, "advanced-weekly-before");
    }

    @Ignore
    @Test
    public void testAdvancedRepeatWeeklyFromCompleteDateCompleteAfter() {
        testAdvancedWeeklyFromCompleteDate(false, "advanced-weekly-after");
    }
}
