/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */

package com.todoroo.astrid.data;

import static com.todoroo.astrid.data.SyncFlags.SUPPRESS_SYNC;
import static java.lang.annotation.RetentionPolicy.SOURCE;
import static org.tasks.Strings.isNullOrEmpty;
import static org.tasks.date.DateTimeUtils.newDateTime;

import android.content.ContentValues;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.IntDef;
import androidx.core.os.ParcelCompat;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import com.google.ical.values.RRule;
import com.todoroo.andlib.data.Property.IntegerProperty;
import com.todoroo.andlib.data.Property.LongProperty;
import com.todoroo.andlib.data.Property.StringProperty;
import com.todoroo.andlib.data.Table;
import com.todoroo.andlib.sql.Field;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.astrid.dao.TaskDao;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import org.tasks.backup.XmlReader;
import org.tasks.data.Tag;
import org.tasks.time.DateTime;
import timber.log.Timber;

@Entity(
    tableName = "tasks",
    indices = {
      @Index(name = "t_rid", value = "remoteId", unique = true),
      @Index(
          name = "active_and_visible",
          value = {"completed", "deleted", "hideUntil"})
    })
public class Task implements Parcelable {

  // --- table and uri

  /** table for this model */
  public static final Table TABLE = new Table("tasks");
  public static final Field FIELDS = Field.field("tasks.*");

  public static final long NO_ID = 0;

  // --- properties
  public static final LongProperty ID = new LongProperty(TABLE, "_id");
  public static final StringProperty TITLE = new StringProperty(TABLE, "title");
  public static final IntegerProperty IMPORTANCE = new IntegerProperty(TABLE, "importance");
  public static final LongProperty DUE_DATE = new LongProperty(TABLE, "dueDate");
  public static final LongProperty HIDE_UNTIL = new LongProperty(TABLE, "hideUntil");
  public static final LongProperty MODIFICATION_DATE = new LongProperty(TABLE, "modified");
  public static final LongProperty CREATION_DATE = new LongProperty(TABLE, "created");
  public static final LongProperty COMPLETION_DATE = new LongProperty(TABLE, "completed");
  public static final LongProperty DELETION_DATE = new LongProperty(TABLE, "deleted");
  public static final StringProperty NOTES = new StringProperty(TABLE, "notes");
  public static final LongProperty TIMER_START = new LongProperty(TABLE, "timerStart");
  public static final LongProperty PARENT = new LongProperty(TABLE, "parent");
  /** constant value for no uuid */
  public static final String NO_UUID = "0"; // $NON-NLS-1$

  public static final StringProperty UUID = new StringProperty(TABLE, "remoteId");
  /** whether to send a reminder at deadline */
  public static final int NOTIFY_AT_DEADLINE = 1 << 1;
  /** whether to send reminders while task is overdue */
  public static final int NOTIFY_AFTER_DEADLINE = 1 << 2;
  /** reminder mode non-stop */
  public static final int NOTIFY_MODE_NONSTOP = 1 << 3;
  /** reminder mode five times (exclusive with non-stop) */
  public static final int NOTIFY_MODE_FIVE = 1 << 4;

  public static final Parcelable.Creator<Task> CREATOR =
      new Parcelable.Creator<Task>() {
        @Override
        public Task createFromParcel(Parcel source) {
          return new Task(source);
        }

        @Override
        public Task[] newArray(int size) {
          return new Task[size];
        }
      };
  /** urgency array index -> significance */
  public static final int URGENCY_NONE = 0;

  public static final int URGENCY_SPECIFIC_DAY = 7;
  public static final int URGENCY_SPECIFIC_DAY_TIME = 8;
  /** hide until array index -> significance */
  public static final int HIDE_UNTIL_NONE = 0;

  public static final int HIDE_UNTIL_DUE = 1;
  public static final int HIDE_UNTIL_DAY_BEFORE = 2;
  public static final int HIDE_UNTIL_WEEK_BEFORE = 3;
  public static final int HIDE_UNTIL_SPECIFIC_DAY = 4;
  // --- for astrid.com
  public static final int HIDE_UNTIL_SPECIFIC_DAY_TIME = 5;
  public static final int HIDE_UNTIL_DUE_TIME = 6;
  static final int URGENCY_TODAY = 1;
  static final int URGENCY_TOMORROW = 2;
  // --- notification flags
  static final int URGENCY_DAY_AFTER = 3;
  static final int URGENCY_NEXT_WEEK = 4;
  static final int URGENCY_IN_TWO_WEEKS = 5;
  /** ID */
  @PrimaryKey(autoGenerate = true)
  @ColumnInfo(name = "_id")
  public transient Long id = NO_ID;

  // --- importance settings (note: importance > 3 are supported via plugin)
  /** Name of Task */
  @ColumnInfo(name = "title")
  public String title = "";

  @ColumnInfo(name = "importance")
  public Integer priority = Priority.NONE;
  /** Unixtime Task is due, 0 if not set */
  @ColumnInfo(name = "dueDate")
  public Long dueDate = 0L;
  /** Unixtime Task should be hidden until, 0 if not set */
  @ColumnInfo(name = "hideUntil")
  public Long hideUntil = 0L;
  /** Unixtime Task was created */
  @ColumnInfo(name = "created")
  public Long created = 0L;
  /** Unixtime Task was last touched */
  @ColumnInfo(name = "modified")
  public Long modified = 0L;
  /** Unixtime Task was completed. 0 means active */
  @ColumnInfo(name = "completed")
  public Long completed = 0L;
  /** Unixtime Task was deleted. 0 means not deleted */
  @ColumnInfo(name = "deleted")
  public Long deleted = 0L;

  // --- data access boilerplate
  @ColumnInfo(name = "notes")
  public String notes = "";

  @ColumnInfo(name = "estimatedSeconds")
  public Integer estimatedSeconds = 0;

  @ColumnInfo(name = "elapsedSeconds")
  public Integer elapsedSeconds = 0;

  @ColumnInfo(name = "timerStart")
  public Long timerStart = 0L;
  /** Flags for when to send reminders */
  @ColumnInfo(name = "notificationFlags")
  public Integer notificationFlags = 0;
  /** Reminder period, in milliseconds. 0 means disabled */
  @ColumnInfo(name = "notifications")
  public Long notifications = 0L;

  // --- parcelable helpers
  /** Unixtime the last reminder was triggered */
  @ColumnInfo(name = "lastNotified")
  public Long lastNotified = 0L;

  // --- data access methods
  /** Unixtime snooze is set (0 -> no snooze) */
  @ColumnInfo(name = "snoozeTime")
  public Long snoozeTime = 0L;

  @ColumnInfo(name = "recurrence")
  public String recurrence = "";

  @ColumnInfo(name = "repeatUntil")
  public Long repeatUntil = 0L;

  @ColumnInfo(name = "calendarUri")
  public String calendarUri = "";
  /** Remote id */
  @ColumnInfo(name = "remoteId")
  public String remoteId = NO_UUID;

  @ColumnInfo(name = "collapsed")
  public boolean collapsed;

  @ColumnInfo(name = "parent")
  public transient long parent;

  @ColumnInfo(name = "parent_uuid")
  public String parentUuid;

  // --- due and hide until date management
  @Ignore private transient HashMap<String, Object> transitoryData = null;

  public Task() {}

  @Ignore
  public Task(XmlReader reader) {
    calendarUri = reader.readString("calendarUri");
    completed = reader.readLong("completed");
    created = reader.readLong("created");
    deleted = reader.readLong("deleted");
    dueDate = reader.readLong("dueDate");
    elapsedSeconds = reader.readInteger("elapsedSeconds");
    estimatedSeconds = reader.readInteger("estimatedSeconds");
    hideUntil = reader.readLong("hideUntil");
    priority = reader.readInteger("importance");
    modified = reader.readLong("modified");
    notes = reader.readString("notes");
    recurrence = reader.readString("recurrence");
    notificationFlags = reader.readInteger("notificationFlags");
    lastNotified = reader.readLong("lastNotified");
    notifications = reader.readLong("notifications");
    snoozeTime = reader.readLong("snoozeTime");
    repeatUntil = reader.readLong("repeatUntil");
    timerStart = reader.readLong("timerStart");
    title = reader.readString("title");
    remoteId = reader.readString("remoteId");
  }

  @Ignore
  public Task(Parcel parcel) {
    calendarUri = parcel.readString();
    completed = parcel.readLong();
    created = parcel.readLong();
    deleted = parcel.readLong();
    dueDate = parcel.readLong();
    elapsedSeconds = parcel.readInt();
    estimatedSeconds = parcel.readInt();
    hideUntil = parcel.readLong();
    id = parcel.readLong();
    priority = parcel.readInt();
    modified = parcel.readLong();
    notes = parcel.readString();
    recurrence = parcel.readString();
    notificationFlags = parcel.readInt();
    lastNotified = parcel.readLong();
    notifications = parcel.readLong();
    snoozeTime = parcel.readLong();
    repeatUntil = parcel.readLong();
    timerStart = parcel.readLong();
    title = parcel.readString();
    remoteId = parcel.readString();
    transitoryData = parcel.readHashMap(ContentValues.class.getClassLoader());
    collapsed = ParcelCompat.readBoolean(parcel);
    parent = parcel.readLong();
    parentUuid = parcel.readString();
  }

  /**
   * Creates due date for this task. If this due date has no time associated, we move it to the last
   * millisecond of the day.
   *
   * @param setting one of the URGENCY_* constants
   * @param customDate if specific day or day & time is set, this value
   */
  public static long createDueDate(int setting, long customDate) {
    long date;

    switch (setting) {
      case URGENCY_NONE:
        date = 0;
        break;
      case URGENCY_TODAY:
        date = DateUtilities.now();
        break;
      case URGENCY_TOMORROW:
        date = DateUtilities.now() + DateUtilities.ONE_DAY;
        break;
      case URGENCY_DAY_AFTER:
        date = DateUtilities.now() + 2 * DateUtilities.ONE_DAY;
        break;
      case URGENCY_NEXT_WEEK:
        date = DateUtilities.now() + DateUtilities.ONE_WEEK;
        break;
      case URGENCY_IN_TWO_WEEKS:
        date = DateUtilities.now() + 2 * DateUtilities.ONE_WEEK;
        break;
      case URGENCY_SPECIFIC_DAY:
      case URGENCY_SPECIFIC_DAY_TIME:
        date = customDate;
        break;
      default:
        throw new IllegalArgumentException("Unknown setting " + setting);
    }

    if (date <= 0) {
      return date;
    }

    DateTime dueDate = newDateTime(date).withMillisOfSecond(0);
    if (setting != URGENCY_SPECIFIC_DAY_TIME) {
      dueDate =
          dueDate
              .withHourOfDay(12)
              .withMinuteOfHour(0)
              .withSecondOfMinute(0); // Seconds == 0 means no due time
    } else {
      dueDate = dueDate.withSecondOfMinute(1); // Seconds > 0 means due time exists
    }
    return dueDate.getMillis();
  }

  /** Checks whether provided due date has a due time or only a date */
  public static boolean hasDueTime(long dueDate) {
    return dueDate > 0 && (dueDate % 60000 > 0);
  }

  public static boolean isValidUuid(String uuid) {
    try {
      long value = Long.parseLong(uuid);
      return value > 0;
    } catch (NumberFormatException e) {
      Timber.e(e);
      return isUuidEmpty(uuid);
    }
  }

  public static boolean isUuidEmpty(String uuid) {
    return NO_UUID.equals(uuid) || isNullOrEmpty(uuid);
  }

  public long getId() {
    return id == null ? NO_ID : id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getUuid() {
    return isNullOrEmpty(remoteId) ? NO_UUID : remoteId;
  }

  public void setUuid(String uuid) {
    remoteId = uuid;
  }

  /** Checks whether task is done. Requires COMPLETION_DATE */
  public boolean isCompleted() {
    return completed > 0;
  }

  /** Checks whether task is deleted. Will return false if DELETION_DATE not read */
  public boolean isDeleted() {
    return deleted > 0;
  }

  /** Checks whether task is hidden. Requires HIDDEN_UNTIL */
  public boolean isHidden() {
    return hideUntil > DateUtilities.now();
  }

  public boolean hasHideUntilDate() {
    return hideUntil > 0;
  }

  /** Checks whether task is done. Requires DUE_DATE */
  public boolean hasDueDate() {
    return dueDate > 0;
  }

  /**
   * Create hide until for this task.
   *
   * @param setting one of the HIDE_UNTIL_* constants
   * @param customDate if specific day is set, this value
   */
  public long createHideUntil(int setting, long customDate) {
    long date;

    switch (setting) {
      case HIDE_UNTIL_NONE:
        return 0;
      case HIDE_UNTIL_DUE:
      case HIDE_UNTIL_DUE_TIME:
        date = dueDate;
        break;
      case HIDE_UNTIL_DAY_BEFORE:
        date = dueDate - DateUtilities.ONE_DAY;
        break;
      case HIDE_UNTIL_WEEK_BEFORE:
        date = dueDate - DateUtilities.ONE_WEEK;
        break;
      case HIDE_UNTIL_SPECIFIC_DAY:
      case HIDE_UNTIL_SPECIFIC_DAY_TIME:
        date = customDate;
        break;
      default:
        throw new IllegalArgumentException("Unknown setting " + setting);
    }

    if (date <= 0) {
      return date;
    }

    DateTime hideUntil = newDateTime(date).withMillisOfSecond(0); // get rid of millis
    if (setting != HIDE_UNTIL_SPECIFIC_DAY_TIME && setting != HIDE_UNTIL_DUE_TIME) {
      hideUntil = hideUntil.withHourOfDay(0).withMinuteOfHour(0).withSecondOfMinute(0);
    } else {
      hideUntil = hideUntil.withSecondOfMinute(1);
    }
    return hideUntil.getMillis();
  }

  /** Checks whether this due date has a due time or only a date */
  public boolean hasDueTime() {
    return hasDueDate() && hasDueTime(getDueDate());
  }

  public boolean isOverdue() {
    long dueDate = getDueDate();
    long compareTo = hasDueTime() ? DateUtilities.now() : newDateTime().startOfDay().getMillis();

    return dueDate < compareTo && !isCompleted();
  }

  public boolean repeatAfterCompletion() {
    return getRecurrence().contains("FROM=COMPLETION");
  }

  public String sanitizedRecurrence() {
    return getRecurrenceWithoutFrom().replaceAll("BYDAY=;", ""); // $NON-NLS-1$//$NON-NLS-2$
  }

  public String getRecurrenceWithoutFrom() {
    return getRecurrence().replaceAll(";?FROM=[^;]*", "");
  }

  public Long getDueDate() {
    return dueDate;
  }

  public void setDueDate(Long dueDate) {
    this.dueDate = dueDate;
  }

  public void setDueDateAdjustingHideUntil(Long newDueDate) {
    if (dueDate > 0) {
      if (hideUntil > 0) {
        setHideUntil(newDueDate > 0 ? hideUntil + newDueDate - dueDate : 0);
      }
    }
    setDueDate(newDueDate);
  }

  public boolean isRecurring() {
    return !isNullOrEmpty(recurrence);
  }

  public String getRecurrence() {
    return recurrence;
  }

  public void setRecurrence(String recurrence) {
    this.recurrence = recurrence;
  }

  public void setRecurrence(RRule rrule, boolean afterCompletion) {
    setRecurrence(rrule.toIcal() + (afterCompletion ? ";FROM=COMPLETION" : ""));
  }

  public Long getCreationDate() {
    return created;
  }

  public void setCreationDate(Long creationDate) {
    created = creationDate;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public Long getDeletionDate() {
    return deleted;
  }

  public void setDeletionDate(Long deletionDate) {
    deleted = deletionDate;
  }

  public Long getHideUntil() {
    return hideUntil;
  }

  public void setHideUntil(Long hideUntil) {
    this.hideUntil = hideUntil;
  }

  public Long getReminderLast() {
    return lastNotified;
  }

  public void setReminderLast(Long reminderLast) {
    lastNotified = reminderLast;
  }

  public Long getReminderSnooze() {
    return snoozeTime;
  }

  public void setReminderSnooze(Long reminderSnooze) {
    snoozeTime = reminderSnooze;
  }

  public Integer getElapsedSeconds() {
    return elapsedSeconds;
  }

  public void setElapsedSeconds(Integer elapsedSeconds) {
    this.elapsedSeconds = elapsedSeconds;
  }

  public Long getTimerStart() {
    return timerStart;
  }

  public void setTimerStart(Long timerStart) {
    this.timerStart = timerStart;
  }

  public Long getRepeatUntil() {
    return repeatUntil;
  }

  public void setRepeatUntil(Long repeatUntil) {
    this.repeatUntil = repeatUntil;
  }

  public String getCalendarURI() {
    return calendarUri;
  }

  public @Priority Integer getPriority() {
    return priority;
  }

  public void setPriority(@Priority Integer priority) {
    this.priority = priority;
  }

  public Long getCompletionDate() {
    return completed;
  }

  public void setCompletionDate(Long completionDate) {
    completed = completionDate;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public boolean hasNotes() {
    return !isNullOrEmpty(notes);
  }

  public Long getModificationDate() {
    return modified;
  }

  public void setModificationDate(Long modificationDate) {
    modified = modificationDate;
  }

  public Integer getReminderFlags() {
    return notificationFlags;
  }

  public void setReminderFlags(Integer reminderFlags) {
    notificationFlags = reminderFlags;
  }

  public Long getReminderPeriod() {
    return notifications;
  }

  public void setReminderPeriod(Long reminderPeriod) {
    notifications = reminderPeriod;
  }

  public Integer getEstimatedSeconds() {
    return estimatedSeconds;
  }

  public void setEstimatedSeconds(Integer estimatedSeconds) {
    this.estimatedSeconds = estimatedSeconds;
  }

  public void setCalendarUri(String calendarUri) {
    this.calendarUri = calendarUri;
  }

  public long getParent() {
    return parent;
  }

  public void setParent(long parent) {
    this.parent = parent;
  }

  public String getParentUuid() {
    return parentUuid;
  }

  public void setParentUuid(String parentUuid) {
    this.parentUuid = parentUuid;
  }

  public boolean isNotifyModeNonstop() {
    return isReminderFlagSet(Task.NOTIFY_MODE_NONSTOP);
  }

  public boolean isNotifyModeFive() {
    return isReminderFlagSet(Task.NOTIFY_MODE_FIVE);
  }

  public boolean isNotifyAfterDeadline() {
    return isReminderFlagSet(Task.NOTIFY_AFTER_DEADLINE);
  }

  public boolean isNotifyAtDeadline() {
    return isReminderFlagSet(Task.NOTIFY_AT_DEADLINE);
  }

  private boolean isReminderFlagSet(int flag) {
    return (notificationFlags & flag) > 0;
  }

  public boolean isNew() {
    return getId() == NO_ID;
  }

  public boolean isCollapsed() {
    return collapsed;
  }

  /** {@inheritDoc} */
  @Override
  public int describeContents() {
    return 0;
  }

  /** {@inheritDoc} */
  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(calendarUri);
    dest.writeLong(completed);
    dest.writeLong(created);
    dest.writeLong(deleted);
    dest.writeLong(dueDate);
    dest.writeInt(elapsedSeconds);
    dest.writeInt(estimatedSeconds);
    dest.writeLong(hideUntil);
    dest.writeLong(id);
    dest.writeInt(priority);
    dest.writeLong(modified);
    dest.writeString(notes);
    dest.writeString(recurrence);
    dest.writeInt(notificationFlags);
    dest.writeLong(lastNotified);
    dest.writeLong(notifications);
    dest.writeLong(snoozeTime);
    dest.writeLong(repeatUntil);
    dest.writeLong(timerStart);
    dest.writeString(title);
    dest.writeString(remoteId);
    dest.writeMap(transitoryData);
    ParcelCompat.writeBoolean(dest, collapsed);
    dest.writeLong(parent);
    dest.writeString(parentUuid);
  }

  @Override
  public String toString() {
    return "Task{"
        + "id="
        + id
        + ", title='"
        + title
        + '\''
        + ", priority="
        + priority
        + ", dueDate="
        + dueDate
        + ", hideUntil="
        + hideUntil
        + ", created="
        + created
        + ", modified="
        + modified
        + ", completed="
        + completed
        + ", deleted="
        + deleted
        + ", notes='"
        + notes
        + '\''
        + ", estimatedSeconds="
        + estimatedSeconds
        + ", elapsedSeconds="
        + elapsedSeconds
        + ", timerStart="
        + timerStart
        + ", notificationFlags="
        + notificationFlags
        + ", notifications="
        + notifications
        + ", lastNotified="
        + lastNotified
        + ", snoozeTime="
        + snoozeTime
        + ", recurrence='"
        + recurrence
        + '\''
        + ", repeatUntil="
        + repeatUntil
        + ", calendarUri='"
        + calendarUri
        + '\''
        + ", remoteId='"
        + remoteId
        + '\''
        + ", collapsed="
        + collapsed
        + ", parent="
        + parent
        + ", parentUuid='"
        + parentUuid
        + '\''
        + ", transitoryData="
        + transitoryData
        + '}';
  }

  public boolean insignificantChange(Task task) {
    if (this == task) {
      return true;
    }
    if (task == null) {
      return false;
    }

    return Objects.equals(id, task.id)
        && Objects.equals(title, task.title)
        && Objects.equals(priority, task.priority)
        && Objects.equals(dueDate, task.dueDate)
        && Objects.equals(hideUntil, task.hideUntil)
        && Objects.equals(created, task.created)
        && Objects.equals(modified, task.modified)
        && Objects.equals(completed, task.completed)
        && Objects.equals(deleted, task.deleted)
        && Objects.equals(notes, task.notes)
        && Objects.equals(estimatedSeconds, task.estimatedSeconds)
        && Objects.equals(elapsedSeconds, task.elapsedSeconds)
        && Objects.equals(notificationFlags, task.notificationFlags)
        && Objects.equals(notifications, task.notifications)
        && Objects.equals(recurrence, task.recurrence)
        && Objects.equals(repeatUntil, task.repeatUntil)
        && Objects.equals(calendarUri, task.calendarUri)
        && parent == task.parent
        && Objects.equals(parentUuid, task.parentUuid)
        && Objects.equals(remoteId, task.remoteId);
  }

  public boolean googleTaskUpToDate(Task original) {
    if (this == original) {
      return true;
    }
    if (original == null) {
      return false;
    }

    return Objects.equals(title, original.title)
        && Objects.equals(dueDate, original.dueDate)
        && Objects.equals(completed, original.completed)
        && Objects.equals(deleted, original.deleted)
        && parent == original.parent
        && Objects.equals(notes, original.notes);
  }

  public boolean caldavUpToDate(Task original) {
    if (this == original) {
      return true;
    }
    if (original == null) {
      return false;
    }

    return Objects.equals(title, original.title)
        && Objects.equals(priority, original.priority)
        && Objects.equals(dueDate, original.dueDate)
        && Objects.equals(completed, original.completed)
        && Objects.equals(deleted, original.deleted)
        && Objects.equals(notes, original.notes)
        && Objects.equals(recurrence, original.recurrence)
        && parent == original.parent
        && Objects.equals(repeatUntil, original.repeatUntil);
  }

  public boolean isSaved() {
    return getId() != NO_ID;
  }

  public synchronized void suppressSync() {
    putTransitory(SUPPRESS_SYNC, true);
  }

  public synchronized void suppressRefresh() {
    putTransitory(TaskDao.TRANS_SUPPRESS_REFRESH, true);
  }

  public synchronized void putTransitory(String key, Object value) {
    if (transitoryData == null) {
      transitoryData = new HashMap<>();
    }
    transitoryData.put(key, value);
  }

  public ArrayList<String> getTags() {
    Object tags = getTransitory(Tag.KEY);
    return tags == null ? new ArrayList<>() : (ArrayList<String>) tags;
  }

  public void setTags(ArrayList<String> tags) {
    if (transitoryData == null) {
      transitoryData = new HashMap<>();
    }
    transitoryData.put(Tag.KEY, tags);
  }

  public boolean hasTransitory(String key) {
    return transitoryData != null && transitoryData.containsKey(key);
  }

  public <T> T getTransitory(String key) {
    if (transitoryData == null) {
      return null;
    }
    return (T) transitoryData.get(key);
  }

  // --- Convenience wrappers for using transitories as flags
  public boolean checkTransitory(String flag) {
    Object trans = getTransitory(flag);
    return trans != null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Task)) {
      return false;
    }
    Task task = (Task) o;
    return collapsed == task.collapsed
        && parent == task.parent
        && Objects.equals(id, task.id)
        && Objects.equals(title, task.title)
        && Objects.equals(priority, task.priority)
        && Objects.equals(dueDate, task.dueDate)
        && Objects.equals(hideUntil, task.hideUntil)
        && Objects.equals(created, task.created)
        && Objects.equals(modified, task.modified)
        && Objects.equals(completed, task.completed)
        && Objects.equals(deleted, task.deleted)
        && Objects.equals(notes, task.notes)
        && Objects.equals(estimatedSeconds, task.estimatedSeconds)
        && Objects.equals(elapsedSeconds, task.elapsedSeconds)
        && Objects.equals(timerStart, task.timerStart)
        && Objects.equals(notificationFlags, task.notificationFlags)
        && Objects.equals(notifications, task.notifications)
        && Objects.equals(lastNotified, task.lastNotified)
        && Objects.equals(snoozeTime, task.snoozeTime)
        && Objects.equals(recurrence, task.recurrence)
        && Objects.equals(repeatUntil, task.repeatUntil)
        && Objects.equals(calendarUri, task.calendarUri)
        && Objects.equals(remoteId, task.remoteId)
        && Objects.equals(parentUuid, task.parentUuid)
        && Objects.equals(transitoryData, task.transitoryData);
  }

  @Override
  public int hashCode() {
    return Objects
        .hash(id, title, priority, dueDate, hideUntil, created, modified, completed, deleted, notes,
            estimatedSeconds, elapsedSeconds, timerStart, notificationFlags, notifications,
            lastNotified, snoozeTime, recurrence, repeatUntil, calendarUri, remoteId, collapsed,
            parent, parentUuid, transitoryData);
  }

  @Retention(SOURCE)
  @IntDef({Priority.HIGH, Priority.MEDIUM, Priority.LOW, Priority.NONE})
  public @interface Priority {
    int HIGH = 0;
    int MEDIUM = 1;
    int LOW = 2;
    int NONE = 3;
  }
}
