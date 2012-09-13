/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.service.abtesting;

import java.util.HashMap;
import java.util.Set;

/**
 * Helper class to define options with their probabilities and descriptions
 * @author Sam Bosley <sam@astrid.com>
 *
 */
public class ABTests {

    public ABTests() {
        bundles = new HashMap<String, ABTestBundle>();
        initialize();
    }

    /**
     * Gets the integer array of weighted probabilities for an option key
     * @param key
     * @return
     */
    public synchronized int[] getProbsForTestKey(String key, boolean newUser) {
        if (bundles.containsKey(key)) {
            ABTestBundle bundle = bundles.get(key);
            if (newUser)
                return bundle.newUserProbs;
            else
                return bundle.existingUserProbs;
        } else {
            return null;
        }
    }

    /**
     * Gets the string array of option descriptions for an option key
     * @param key
     * @return
     */
    public String[] getDescriptionsForTestKey(String key) {
        if (bundles.containsKey(key)) {
            ABTestBundle bundle = bundles.get(key);
            return bundle.descriptions;
        } else {
            return null;
        }
    }

    /**
     * Returns the description for a particular choice of the given option
     * @param testKey
     * @param optionIndex
     * @return
     */
    public String getDescriptionForTestOption(String testKey, int optionIndex) {
        if (bundles.containsKey(testKey)) {
            ABTestBundle bundle = bundles.get(testKey);
            if (bundle.descriptions != null && optionIndex < bundle.descriptions.length) {
                return bundle.descriptions[optionIndex];
            }
        }
        return null;
    }

    public Set<String> getAllTestKeys() {
        return bundles.keySet();
    }

    /**
     * Maps keys (i.e. preference key identifiers) to feature weights and descriptions
     */
    private final HashMap<String, ABTestBundle> bundles;

    private static class ABTestBundle {
        protected final int[] newUserProbs;
        protected final int[] existingUserProbs;
        protected final String[] descriptions;

        protected ABTestBundle(int[] newUserProbs, int[] existingUserProbs, String[] descriptions) {
            this.newUserProbs = newUserProbs;
            this.existingUserProbs = existingUserProbs;
            this.descriptions = descriptions;
        }
    }

    public boolean isValidTestKey(String key) {
        return bundles.containsKey(key);
    }

    /**
     * A/B testing options are defined below according to the following spec:
     *
     * @param testKey = "<key>"
     * --This key is used to identify the option in the application and in the preferences
     *
     * @param newUserProbs = { int, int, ... }
     * @param existingUserProbs = { int, int, ... }
     * --The different choices in an option correspond to an index in the probability array.
     * Probabilities are expressed as integers to easily define relative weights. For example,
     * the array { 1, 2 } would mean option 0 would happen one time for every two occurrences of option 1
     *
     * The first array is used for new users and the second is used for existing/upgrading users,
     * allowing us to specify different distributions for each group.
     *
     * (optional)
     * @param descriptions = { "...", "...", ... }
     * --A string description of each option. Useful for tagging events. The index of
     * each description should correspond to the events location in the probability array
     * (i.e. the arrays should be the same length if this one exists)
     *
     */
    public void addTest(String testKey, int[] newUserProbs, int[] existingUserProbs, String[] descriptions) {
        ABTestBundle bundle = new ABTestBundle(newUserProbs, existingUserProbs, descriptions);
        bundles.put(testKey, bundle);
    }

    public static final String AB_TASK_EDIT_TOAST = "android_task_edit_toast"; //$NON-NLS-1$

    public static final String AB_SWIPE_BETWEEN = "android_swipe_v3"; //$NON-NLS-1$

    public static final String AB_SIMPLE_TASK_ROW = "android_simple_task_row";  //$NON-NLS-1$

    private void initialize() {
        addTest(AB_TASK_EDIT_TOAST, new int[] { 1, 1 },
                new int[] { 1, 1 }, new String[] { "dont-show-toasts", "show-toasts" });  //$NON-NLS-1$ //$NON-NLS-2$

        addTest(AB_SIMPLE_TASK_ROW, new int[] { 1, 1 },
                new int[] { 1, 0 }, new String[] { "original-row-style", "simple-row-style" });//$NON-NLS-1$ //$NON-NLS-2$

        // AB_SWIPE_BETWEEN has to be added in the startup service since it needs a non-null context for initialization
    }
}
