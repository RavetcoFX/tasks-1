/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */

package com.todoroo.astrid.core;

import static org.tasks.db.QueryUtils.showCompleted;
import static org.tasks.db.QueryUtils.showHidden;
import static org.tasks.db.QueryUtils.showRecentlyCompleted;

import com.todoroo.andlib.sql.Functions;
import com.todoroo.andlib.sql.Order;
import com.todoroo.astrid.data.Task;
import org.tasks.R;
import org.tasks.preferences.Preferences;

/**
 * Helpers for sorting a list of tasks
 *
 * @author Tim Su <tim@todoroo.com>
 */
public class SortHelper {

  public static final int SORT_AUTO = 0;
  public static final int SORT_ALPHA = 1;
  public static final int SORT_DUE = 2;
  public static final int SORT_IMPORTANCE = 3;
  public static final int SORT_MODIFIED = 4;
  public static final int SORT_CREATED = 5;

  private static final String ADJUSTED_DUE_DATE =
      "(CASE WHEN (dueDate / 1000) % 60 > 0 THEN dueDate ELSE (dueDate + 43140000) END)";
  private static final Order ORDER_TITLE = Order.asc(Functions.upper(Task.TITLE));

  /** Takes a SQL query, and if there isn't already an order, creates an order. */
  public static String adjustQueryForFlagsAndSort(
      Preferences preferences, String originalSql, int sort) {
    // sort
    if (originalSql == null) {
      originalSql = "";
    }
    if (!originalSql.toUpperCase().contains("ORDER BY")) {
      Order order = orderForSortType(sort);

      if (preferences.isReverseSort()) {
        order = order.reverse();
      }
      originalSql += " ORDER BY " + order;
    }

    return adjustQueryForFlags(preferences, originalSql);
  }

  public static String adjustQueryForFlags(
          Preferences preferences, String originalSql) {
    String adjustedSql = originalSql;

    // flags
    if (preferences.getBoolean(R.string.p_show_completed_tasks, false)) {
      adjustedSql = showCompleted(adjustedSql);
    } else if (preferences.getBoolean(R.string.p_temporarily_show_completed_tasks, false)) {
      adjustedSql = showRecentlyCompleted(adjustedSql);
    }
    if (preferences.getBoolean(R.string.p_show_hidden_tasks, false)) {
      adjustedSql = showHidden(adjustedSql);
    }

    return adjustedSql;
  }

  private static Order orderForSortType(int sortType) {
    Order order;
    switch (sortType) {
      case SORT_ALPHA:
        order = ORDER_TITLE;
        break;
      case SORT_DUE:
        order =
            Order.asc(
                "(CASE WHEN (dueDate=0) THEN (strftime('%s','now')*1000)*2 ELSE "
                    + ADJUSTED_DUE_DATE
                    + " END)+importance");
        break;
      case SORT_IMPORTANCE:
        order =
            Order.asc(
                "importance*(strftime('%s','now')*1000)+(CASE WHEN (dueDate=0) THEN (strftime('%s','now')*1000) ELSE dueDate END)");
        break;
      case SORT_MODIFIED:
        order = Order.desc(Task.MODIFICATION_DATE);
        break;
      case SORT_CREATED:
        order = Order.desc(Task.CREATION_DATE);
        break;
      default:
        order =
            Order.asc(
                "(CASE WHEN (dueDate=0) "
                    + // if no due date
                    "THEN (strftime('%s','now')*1000)*2 "
                    + // then now * 2
                    "ELSE ("
                    + ADJUSTED_DUE_DATE
                    + ") END) "
                    + // else due time
                    "+ 172800000 * importance"); // add 2 days * importance
    }
    if (sortType != SORT_ALPHA) {
      order.addSecondaryExpression(ORDER_TITLE);
    }

    return order;
  }

  public static String orderSelectForSortTypeRecursive(int sortType) {
    String select;
    switch (sortType) {
      case SORT_ALPHA:
        // Return an empty string, providing a value to fill the WITH clause template
        select = "''";
        break;
      case SORT_DUE:
        select = "(CASE WHEN (tasks.dueDate=0) THEN (strftime('%s','now')*1000)*2 ELSE "
                   + ADJUSTED_DUE_DATE.replace("dueDate", "tasks.dueDate")
                   + " END)+tasks.importance AS sort_duedate";
        break;
      case SORT_IMPORTANCE:
        select = "tasks.importance*(strftime('%s','now')*1000)+(CASE WHEN (tasks.dueDate=0) THEN (strftime('%s','now')*1000) ELSE tasks.dueDate END) AS sort_importance";
        break;
      case SORT_MODIFIED:
        select = "tasks.modified AS sort_modified";
        break;
      case SORT_CREATED:
        select = "tasks.created AS sort_created";
        break;
      default:
        select ="(CASE WHEN (tasks.dueDate=0) "
                    + // if no due date
                    "THEN (strftime('%s','now')*1000)*2 "
                    + // then now * 2
                    "ELSE ("
                    + ADJUSTED_DUE_DATE.replace("dueDate", "tasks.dueDate")
                    + ") END) "
                    + // else due time
                    "+ 172800000 * tasks.importance AS sort_smart"; // add 2 days * importance
    }

    return select;
  }

  public static Order orderForSortTypeRecursive(Preferences preferences) {
    Order order;
    switch (preferences.getSortMode()) {
      case SORT_ALPHA:
        order = Order.asc("sort_title");
        break;
      case SORT_DUE:
        order = Order.asc("sort_duedate");
        break;
      case SORT_IMPORTANCE:
        order = Order.asc("sort_importance");
        break;
      case SORT_MODIFIED:
        order = Order.desc("sort_modified");
        break;
      case SORT_CREATED:
        order = Order.desc("sort_created");
        break;
      default:
        order = Order.asc("sort_smart");
    }
    if (preferences.getSortMode() != SORT_ALPHA) {
      order.addSecondaryExpression(Order.asc("sort_title"));
    }

    if (preferences.isReverseSort()) {
      order = order.reverse();
    }

    return order;
  }
}
