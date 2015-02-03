/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.app.ActivityOptions;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewStub;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsPackageMonitor;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout implements TaskStackView.TaskStackViewCallbacks,
        RecentsPackageMonitor.PackageCallbacks {

    /** The RecentsView callbacks */
    public interface RecentsViewCallbacks {
        public void onTaskViewClicked();
        public void onTaskLaunchFailed();
        public void onAllTaskViewsDismissed();
        public void onExitToHomeAnimationTriggered();
        public void onScreenPinningRequest();

        public void onMultiStackAddStack();
        public void onMultiStackResizeStack();
        public void onMultiStackRemoveStack();
        public void onMultiStackMoveTask(Task t);
    }

    RecentsConfiguration mConfig;
    LayoutInflater mInflater;
    DebugOverlayView mDebugOverlay;
    ViewStub mMultiStackDebugStub;
    View mMultiStackDebugView;
    RecentsViewLayoutAlgorithm mLayoutAlgorithm;

    ArrayList<TaskStack> mStacks;
    List<TaskStackView> mTaskStackViews = new ArrayList<>();
    View mSearchBar;
    RecentsViewCallbacks mCb;

    public RecentsView(Context context) {
        super(context);
    }

    public RecentsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        mInflater = LayoutInflater.from(context);
        mLayoutAlgorithm = new RecentsViewLayoutAlgorithm(mConfig);
    }

    @Override
    protected void onFinishInflate() {
        if (!mConfig.multiStackEnabled) return;

        mMultiStackDebugStub = (ViewStub) findViewById(R.id.multistack_debug_view_stub);
        if (mMultiStackDebugView == null) {
            mMultiStackDebugView = mMultiStackDebugStub.inflate();
            mMultiStackDebugView.findViewById(R.id.add_stack).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCb.onMultiStackAddStack();
                }
            });
            mMultiStackDebugView.findViewById(R.id.resize_stack).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mCb.onMultiStackResizeStack();
                }
            });
        }
    }

    /** Sets the callbacks */
    public void setCallbacks(RecentsViewCallbacks cb) {
        mCb = cb;
    }

    /** Sets the debug overlay */
    public void setDebugOverlay(DebugOverlayView overlay) {
        mDebugOverlay = overlay;
    }

    /** Set/get the bsp root node */
    public void setTaskStacks(ArrayList<TaskStack> stacks) {
        int numStacks = stacks.size();

        // Remove all/extra stack views
        int numTaskStacksToKeep = 0; // Keep no tasks if we are recreating the layout
        if (mConfig.launchedReuseTaskStackViews) {
            numTaskStacksToKeep = Math.min(mTaskStackViews.size(), numStacks);
        }
        for (int i = mTaskStackViews.size() - 1; i >= numTaskStacksToKeep; i--) {
            removeView(mTaskStackViews.remove(i));
        }

        // Update the stack views that we are keeping
        for (int i = 0; i < numTaskStacksToKeep; i++) {
            TaskStackView tsv = mTaskStackViews.get(i);
            // If onRecentsHidden is not triggered, we need to the stack view again here
            tsv.reset();
            tsv.setStack(stacks.get(i));
        }

        // Add remaining/recreate stack views
        mStacks = stacks;
        for (int i = mTaskStackViews.size(); i < numStacks; i++) {
            TaskStack stack = stacks.get(i);
            TaskStackView stackView = new TaskStackView(getContext(), stack);
            stackView.setCallbacks(this);
            addView(stackView);
            mTaskStackViews.add(stackView);
        }

        // Enable debug mode drawing on all the stacks if necessary
        if (mConfig.debugModeEnabled) {
            for (int i = mTaskStackViews.size() - 1; i >= 0; i--) {
                TaskStackView stackView = mTaskStackViews.get(i);
                stackView.setDebugOverlay(mDebugOverlay);
            }
        }

        // Bring the debug view to the front
        if (mMultiStackDebugView != null) {
            mMultiStackDebugView.bringToFront();
        }

        // Trigger a new layout
        requestLayout();
    }

    /** Gets the list of task views */
    List<TaskStackView> getTaskStackViews() {
        return mTaskStackViews;
    }

    /** Launches the focused task from the first stack if possible */
    public boolean launchFocusedTask() {
        // Get the first stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            TaskStack stack = stackView.getStack();
            // Iterate the stack views and try and find the focused task
            List<TaskView> taskViews = stackView.getTaskViews();
            int taskViewCount = taskViews.size();
            for (int j = 0; j < taskViewCount; j++) {
                TaskView tv = taskViews.get(j);
                Task task = tv.getTask();
                if (tv.isFocusedTask()) {
                    onTaskViewClicked(stackView, tv, stack, task, false);
                    return true;
                }
            }
        }
        return false;
    }

    /** Launches the task that Recents was launched from, if possible */
    public boolean launchPreviousTask() {
        // Get the first stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            TaskStack stack = stackView.getStack();
            ArrayList<Task> tasks = stack.getTasks();

            // Find the launch task in the stack
            if (!tasks.isEmpty()) {
                int taskCount = tasks.size();
                for (int j = 0; j < taskCount; j++) {
                    if (tasks.get(j).isLaunchTarget) {
                        Task task = tasks.get(j);
                        TaskView tv = stackView.getChildViewForTask(task);
                        onTaskViewClicked(stackView, tv, stack, task, false);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Requests all task stacks to start their enter-recents animation */
    public void startEnterRecentsAnimation(ViewAnimation.TaskViewEnterContext ctx) {
        // We have to increment/decrement the post animation trigger in case there are no children
        // to ensure that it runs
        ctx.postAnimationTrigger.increment();

        List<TaskStackView> stackViews = getTaskStackViews();
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            stackView.startEnterRecentsAnimation(ctx);
        }
        ctx.postAnimationTrigger.decrement();
    }

    /** Requests all task stacks to start their exit-recents animation */
    public void startExitToHomeAnimation(ViewAnimation.TaskViewExitContext ctx) {
        // We have to increment/decrement the post animation trigger in case there are no children
        // to ensure that it runs
        ctx.postAnimationTrigger.increment();
        List<TaskStackView> stackViews = getTaskStackViews();
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            stackView.startExitToHomeAnimation(ctx);
        }
        ctx.postAnimationTrigger.decrement();

        // Notify of the exit animation
        mCb.onExitToHomeAnimationTriggered();
    }

    /** Adds the search bar */
    public void setSearchBar(View searchBar) {
        // Create the search bar (and hide it if we have no recent tasks)
        if (Constants.DebugFlags.App.EnableSearchLayout) {
            // Remove the previous search bar if one exists
            if (mSearchBar != null && indexOfChild(mSearchBar) > -1) {
                removeView(mSearchBar);
            }
            // Add the new search bar
            if (searchBar != null) {
                mSearchBar = searchBar;
                addView(mSearchBar);
            }
        }
    }

    /** Returns whether there is currently a search bar */
    public boolean hasSearchBar() {
        return mSearchBar != null;
    }

    /** Sets the visibility of the search bar */
    public void setSearchBarVisibility(int visibility) {
        if (mSearchBar != null) {
            mSearchBar.setVisibility(visibility);
            // Always bring the search bar to the top
            mSearchBar.bringToFront();
        }
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Get the search bar bounds and measure the search bar layout
        if (mSearchBar != null) {
            Rect searchBarSpaceBounds = new Rect();
            mConfig.getSearchBarBounds(width, height, mConfig.systemInsets.top, searchBarSpaceBounds);
            mSearchBar.measure(
                    MeasureSpec.makeMeasureSpec(searchBarSpaceBounds.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(searchBarSpaceBounds.height(), MeasureSpec.EXACTLY));
        }

        Rect taskStackBounds = new Rect();
        mConfig.getAvailableTaskStackBounds(width, height, mConfig.systemInsets.top,
                mConfig.systemInsets.right, taskStackBounds);

        // Measure each TaskStackView with the full width and height of the window since the
        // transition view is a child of that stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        List<Rect> stackViewsBounds = mLayoutAlgorithm.computeStackRects(stackViews,
                taskStackBounds);
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            if (stackView.getVisibility() != GONE) {
                // We are going to measure the TaskStackView with the whole RecentsView dimensions,
                // but the actual stack is going to be inset to the bounds calculated by the layout
                // algorithm
                stackView.setStackInsetRect(stackViewsBounds.get(i));
                stackView.measure(widthMeasureSpec, heightMeasureSpec);
            }
        }

        // Measure the multistack debug view
        if (mMultiStackDebugView != null) {
            mMultiStackDebugView.measure(width, height);
        }

        setMeasuredDimension(width, height);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Get the search bar bounds so that we lay it out
        if (mSearchBar != null) {
            Rect searchBarSpaceBounds = new Rect();
            mConfig.getSearchBarBounds(getMeasuredWidth(), getMeasuredHeight(),
                    mConfig.systemInsets.top, searchBarSpaceBounds);
            mSearchBar.layout(searchBarSpaceBounds.left, searchBarSpaceBounds.top,
                    searchBarSpaceBounds.right, searchBarSpaceBounds.bottom);
        }

        // Layout each TaskStackView with the full width and height of the window since the 
        // transition view is a child of that stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            if (stackView.getVisibility() != GONE) {
                stackView.layout(left, top, left + stackView.getMeasuredWidth(),
                        top + stackView.getMeasuredHeight());
            }
        }

        // Layout the multistack debug view
        if (mMultiStackDebugView != null) {
            Rect taskStackBounds = new Rect();
            mConfig.getAvailableTaskStackBounds(getMeasuredWidth(), getMeasuredHeight(),
                    mConfig.systemInsets.top, mConfig.systemInsets.right, taskStackBounds);
            mMultiStackDebugView.layout(left,
                    taskStackBounds.bottom - mConfig.systemInsets.bottom -
                            mMultiStackDebugView.getMeasuredHeight(),
                    left + mMultiStackDebugView.getMeasuredWidth(),
                    taskStackBounds.bottom - mConfig.systemInsets.bottom);
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // Update the configuration with the latest system insets and trigger a relayout
        mConfig.updateSystemInsets(insets.getSystemWindowInsets());
        requestLayout();
        return insets.consumeSystemWindowInsets();
    }

    /** Notifies each task view of the user interaction. */
    public void onUserInteraction() {
        // Get the first stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            stackView.onUserInteraction();
        }
    }

    /** Focuses the next task in the first stack view */
    public void focusNextTask(boolean forward) {
        // Get the first stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        if (!stackViews.isEmpty()) {
            stackViews.get(0).focusNextTask(forward, true);
        }
    }

    /** Dismisses the focused task. */
    public void dismissFocusedTask() {
        // Get the first stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        if (!stackViews.isEmpty()) {
            stackViews.get(0).dismissFocusedTask();
        }
    }

    /** Unfilters any filtered stacks */
    public boolean unfilterFilteredStacks() {
        if (mStacks != null) {
            // Check if there are any filtered stacks and unfilter them before we back out of Recents
            boolean stacksUnfiltered = false;
            int numStacks = mStacks.size();
            for (int i = 0; i < numStacks; i++) {
                TaskStack stack = mStacks.get(i);
                if (stack.hasFilteredTasks()) {
                    stack.unfilterTasks();
                    stacksUnfiltered = true;
                }
            }
            return stacksUnfiltered;
        }
        return false;
    }

    /**** TaskStackView.TaskStackCallbacks Implementation ****/

    @Override
    public void onTaskViewClicked(final TaskStackView stackView, final TaskView tv,
                                  final TaskStack stack, final Task task, final boolean lockToTask) {
        // Notify any callbacks of the launching of a new task
        if (mCb != null) {
            mCb.onTaskViewClicked();
        }

        // Upfront the processing of the thumbnail
        TaskViewTransform transform = new TaskViewTransform();
        View sourceView;
        int offsetX = 0;
        int offsetY = 0;
        float stackScroll = stackView.getScroller().getStackScroll();
        if (tv == null) {
            // If there is no actual task view, then use the stack view as the source view
            // and then offset to the expected transform rect, but bound this to just
            // outside the display rect (to ensure we don't animate from too far away)
            sourceView = stackView;
            transform = stackView.getStackAlgorithm().getStackTransform(task, stackScroll, transform, null);
            offsetX = transform.rect.left;
            offsetY = mConfig.displayRect.height();
        } else {
            sourceView = tv.mThumbnailView;
            transform = stackView.getStackAlgorithm().getStackTransform(task, stackScroll, transform, null);
        }

        // Compute the thumbnail to scale up from
        final SystemServicesProxy ssp =
                RecentsTaskLoader.getInstance().getSystemServicesProxy();
        ActivityOptions opts = null;
        if (task.thumbnail != null && task.thumbnail.getWidth() > 0 &&
                task.thumbnail.getHeight() > 0) {
            Bitmap b;
            if (tv != null) {
                // Disable any focused state before we draw the header
                if (tv.isFocusedTask()) {
                    tv.unsetFocusedTask();
                }

                float scale = tv.getScaleX();
                int fromHeaderWidth = (int) (tv.mHeaderView.getMeasuredWidth() * scale);
                int fromHeaderHeight = (int) (tv.mHeaderView.getMeasuredHeight() * scale);
                b = Bitmap.createBitmap(fromHeaderWidth, fromHeaderHeight,
                        Bitmap.Config.ARGB_8888);
                if (Constants.DebugFlags.App.EnableTransitionThumbnailDebugMode) {
                    b.eraseColor(0xFFff0000);
                } else {
                    Canvas c = new Canvas(b);
                    c.scale(tv.getScaleX(), tv.getScaleY());
                    tv.mHeaderView.draw(c);
                    c.setBitmap(null);
                }
            } else {
                // Notify the system to skip the thumbnail layer by using an ALPHA_8 bitmap
                b = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
            }
            ActivityOptions.OnAnimationStartedListener animStartedListener = null;
            if (lockToTask) {
                animStartedListener = new ActivityOptions.OnAnimationStartedListener() {
                    boolean mTriggered = false;
                    @Override
                    public void onAnimationStarted() {
                        if (!mTriggered) {
                            postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    mCb.onScreenPinningRequest();
                                }
                            }, 350);
                            mTriggered = true;
                        }
                    }
                };
            }
            if (mConfig.multiStackEnabled) {
                opts = ActivityOptions.makeCustomAnimation(sourceView.getContext(),
                        R.anim.recents_from_unknown_enter,
                        R.anim.recents_from_unknown_exit,
                        sourceView.getHandler(), animStartedListener);
            } else {
                opts = ActivityOptions.makeThumbnailAspectScaleUpAnimation(sourceView,
                        b, offsetX, offsetY, transform.rect.width(), transform.rect.height(),
                        sourceView.getHandler(), animStartedListener);
            }
        }

        final ActivityOptions launchOpts = opts;
        final Runnable launchRunnable = new Runnable() {
            @Override
            public void run() {
                if (task.isActive) {
                    // Bring an active task to the foreground
                    ssp.moveTaskToFront(task.key.id, launchOpts);
                } else {
                    if (ssp.startActivityFromRecents(getContext(), task.key.id,
                            task.activityLabel, launchOpts)) {
                        if (launchOpts == null && lockToTask) {
                            mCb.onScreenPinningRequest();
                        }
                    } else {
                        // Dismiss the task and return the user to home if we fail to
                        // launch the task
                        onTaskViewDismissed(task);
                        if (mCb != null) {
                            mCb.onTaskLaunchFailed();
                        }
                    }
                }
            }
        };

        // Launch the app right away if there is no task view, otherwise, animate the icon out first
        if (tv == null) {
            launchRunnable.run();
        } else {
            if (!task.group.isFrontMostTask(task)) {
                // For affiliated tasks that are behind other tasks, we must animate the front cards
                // out of view before starting the task transition
                stackView.startLaunchTaskAnimation(tv, launchRunnable, lockToTask);
            } else {
                // Otherwise, we can start the task transition immediately
                stackView.startLaunchTaskAnimation(tv, null, lockToTask);
                launchRunnable.run();
            }
        }
    }

    @Override
    public void onTaskViewAppInfoClicked(Task t) {
        // Create a new task stack with the application info details activity
        Intent baseIntent = t.key.baseIntent;
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", baseIntent.getComponent().getPackageName(), null));
        intent.setComponent(intent.resolveActivity(getContext().getPackageManager()));
        TaskStackBuilder.create(getContext())
                .addNextIntentWithParentStack(intent).startActivities(null,
                new UserHandle(t.key.userId));
    }

    @Override
    public void onTaskViewDismissed(Task t) {
        // Remove any stored data from the loader.  We currently don't bother notifying the views
        // that the data has been unloaded because at the point we call onTaskViewDismissed(), the views
        // either don't need to be updated, or have already been removed.
        RecentsTaskLoader loader = RecentsTaskLoader.getInstance();
        loader.deleteTaskData(t, false);

        // Remove the old task from activity manager
        loader.getSystemServicesProxy().removeTask(t.key.id);
    }

    @Override
    public void onAllTaskViewsDismissed(ArrayList<Task> removedTasks) {
        if (removedTasks != null) {
            int taskCount = removedTasks.size();
            for (int i = 0; i < taskCount; i++) {
                onTaskViewDismissed(removedTasks.get(i));
            }
        }

        mCb.onAllTaskViewsDismissed();
    }

    /** Final callback after Recents is finally hidden. */
    public void onRecentsHidden() {
        // Notify each task stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            stackView.onRecentsHidden();
        }
    }

    @Override
    public void onTaskStackFilterTriggered() {
        // Hide the search bar
        if (mSearchBar != null) {
            mSearchBar.animate()
                    .alpha(0f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .setDuration(mConfig.filteringCurrentViewsAnimDuration)
                    .withLayer()
                    .start();
        }
    }

    @Override
    public void onTaskStackUnfilterTriggered() {
        // Show the search bar
        if (mSearchBar != null) {
            mSearchBar.animate()
                    .alpha(1f)
                    .setStartDelay(0)
                    .setInterpolator(mConfig.fastOutSlowInInterpolator)
                    .setDuration(mConfig.filteringNewViewsAnimDuration)
                    .withLayer()
                    .start();
        }
    }

    @Override
    public void onMultiStackMoveTask(Task t) {
        if (mCb != null) {
            mCb.onMultiStackMoveTask(t);
        }
    }

    /**** RecentsPackageMonitor.PackageCallbacks Implementation ****/

    @Override
    public void onPackagesChanged(RecentsPackageMonitor monitor, String packageName, int userId) {
        // Propagate this event down to each task stack view
        List<TaskStackView> stackViews = getTaskStackViews();
        int stackCount = stackViews.size();
        for (int i = 0; i < stackCount; i++) {
            TaskStackView stackView = stackViews.get(i);
            stackView.onPackagesChanged(monitor, packageName, userId);
        }
    }
}
