/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.reminders;

import android.app.Notification;
import android.content.Intent;

import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.NotificationManager;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.test.DatabaseTestCase;

import org.tasks.R;

import static org.tasks.date.DateTimeUtils.newDate;

public class NotificationTests extends DatabaseTestCase {

    @Autowired TaskDao taskDao;

    public class MutableBoolean {
        boolean value = false;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Notifications.forceNotificationManager();
    }

    @Override
    protected void tearDown() throws Exception {
        Notifications.setNotificationManager(null);
    }

    /** test that a normal task gets a notification */
    public void testAlarmToNotification() {
        final Task task = new Task();
        task.setTitle("rubberduck");
        task.setDueDate(DateUtilities.now() - DateUtilities.ONE_DAY);
        taskDao.persist(task);

        final MutableBoolean triggered = new MutableBoolean();

        Notifications.setNotificationManager(new TestNotificationManager() {
            public void notify(int id, Notification notification) {
                assertNotNull(notification.contentIntent);
                triggered.value = true;
            }

        });

        Intent intent = new Intent();
        intent.putExtra(Notifications.ID_KEY, task.getId());
        intent.putExtra(Notifications.EXTRAS_TYPE, ReminderService.TYPE_DUE);
        new Notifications().onReceive(getContext(), intent);
        assertTrue(triggered.value);
    }

    /** test that a deleted task doesn't get a notification */
    public void testDeletedTask() {
        final Task task = new Task();
        task.setTitle("gooeyduck");
        task.setDeletionDate(DateUtilities.now());
        taskDao.persist(task);

        Notifications.setNotificationManager(new NotificationManager() {

            public void cancel(int id) {
                // allowed
            }

            public void notify(int id, Notification notification) {
                fail("sent a notification, you shouldn't have...");
            }

        });

        Intent intent = new Intent();
        intent.putExtra(Notifications.ID_KEY, task.getId());
        intent.putExtra(Notifications.EXTRAS_TYPE, ReminderService.TYPE_DUE);
        new Notifications().onReceive(getContext(), intent);
    }

    /** test that a completed task doesn't get a notification */
    public void testCompletedTask() {
        final Task task = new Task();
        task.setTitle("rubberduck");
        task.setCompletionDate(DateUtilities.now());
        taskDao.persist(task);

        Notifications.setNotificationManager(new NotificationManager() {

            public void cancel(int id) {
                // allowed
            }

            public void notify(int id, Notification notification) {
                fail("sent a notification, you shouldn't have...");
            }

        });

        Intent intent = new Intent();
        intent.putExtra(Notifications.ID_KEY, task.getId());
        intent.putExtra(Notifications.EXTRAS_TYPE, ReminderService.TYPE_DUE);
        new Notifications().onReceive(getContext(), intent);
    }

    /** test of quiet hours */
    public void testQuietHours() {
        final Task task = new Task();
        task.setTitle("rubberduck");
        taskDao.persist(task);
        Intent intent = new Intent();
        intent.putExtra(Notifications.ID_KEY, task.getId());

        int hour = newDate().getHours();
        Preferences.setStringFromInteger(R.string.p_rmd_quietStart, hour - 1);
        Preferences.setStringFromInteger(R.string.p_rmd_quietEnd, hour + 1);

        // due date notification has vibrate
        Notifications.setNotificationManager(new TestNotificationManager() {
            public void notify(int id, Notification notification) {
                assertNull(notification.sound);
                assertTrue((notification.defaults & Notification.DEFAULT_SOUND) == 0);
                assertNotNull(notification.vibrate);
                assertTrue(notification.vibrate.length > 0);
            }
        });
        intent.putExtra(Notifications.EXTRAS_TYPE, ReminderService.TYPE_DUE);
        new Notifications().onReceive(getContext(), intent);

        // random notification does not
        Notifications.setNotificationManager(new TestNotificationManager() {
            public void notify(int id, Notification notification) {
                assertNull(notification.sound);
                assertTrue((notification.defaults & Notification.DEFAULT_SOUND) == 0);
                assertTrue(notification.vibrate == null ||
                        notification.vibrate.length == 0);
            }
        });
        intent.removeExtra(Notifications.EXTRAS_TYPE);
        intent.putExtra(Notifications.EXTRAS_TYPE, ReminderService.TYPE_RANDOM);
        new Notifications().onReceive(getContext(), intent);

        // wrapping works
        Preferences.setStringFromInteger(R.string.p_rmd_quietStart, hour + 2);
        Preferences.setStringFromInteger(R.string.p_rmd_quietEnd, hour + 1);

        Notifications.setNotificationManager(new TestNotificationManager() {
            public void notify(int id, Notification notification) {
                assertNull(notification.sound);
                assertTrue((notification.defaults & Notification.DEFAULT_SOUND) == 0);
            }
        });
        intent.removeExtra(Notifications.EXTRAS_TYPE);
        intent.putExtra(Notifications.EXTRAS_TYPE, ReminderService.TYPE_DUE);
        new Notifications().onReceive(getContext(), intent);

        // nonstop notification still sounds
        task.setReminderFlags(Task.NOTIFY_MODE_NONSTOP);
        taskDao.persist(task);
        Notifications.setNotificationManager(new TestNotificationManager() {
            public void notify(int id, Notification notification) {
                assertTrue(notification.sound != null ||
                        (notification.defaults & Notification.DEFAULT_SOUND) > 0);
            }
        });
        new Notifications().onReceive(getContext(), intent);
    }

    abstract public class TestNotificationManager implements NotificationManager {
        public void cancel(int id) {
            fail("wtf cance?");
        }
    }

}
