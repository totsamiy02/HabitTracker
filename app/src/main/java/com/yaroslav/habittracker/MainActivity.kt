package com.yaroslav.habittracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yaroslav.habittracker.calendar.CalendarScreen
import com.yaroslav.habittracker.calendar.CalendarViewModel
import com.yaroslav.habittracker.calendar.DayPlan
import com.yaroslav.habittracker.habits.AllHabitsScreen
import com.yaroslav.habittracker.habits.BulkMarkScreen
import com.yaroslav.habittracker.habits.HabitDetailsScreen
import com.yaroslav.habittracker.habits.HabitEditorScreen
import com.yaroslav.habittracker.habits.HabitProgress
import com.yaroslav.habittracker.habits.HabitsState
import com.yaroslav.habittracker.habits.HabitsViewModel
import com.yaroslav.habittracker.habits.HistoryScreen
import com.yaroslav.habittracker.habits.ManualTimeScreen
import com.yaroslav.habittracker.habits.Screen
import com.yaroslav.habittracker.habits.Tab
import com.yaroslav.habittracker.planning.OPEN_PLAN_CALENDAR
import com.yaroslav.habittracker.planning.WeekScreen
import com.yaroslav.habittracker.planning.WeekViewModel
import com.yaroslav.habittracker.planning.reminderDate
import com.yaroslav.habittracker.settings.ExportScreen
import com.yaroslav.habittracker.settings.RestoreScreen
import com.yaroslav.habittracker.settings.RestoreViewModel
import com.yaroslav.habittracker.settings.SettingsScreen
import com.yaroslav.habittracker.settings.SettingsViewModel
import com.yaroslav.habittracker.settings.UpdateScreen
import com.yaroslav.habittracker.tasks.TaskScreen
import com.yaroslav.habittracker.tasks.TaskViewModel
import com.yaroslav.habittracker.timer.ActiveTimerBar
import com.yaroslav.habittracker.timer.OPEN_TIMER
import com.yaroslav.habittracker.timer.OPEN_TIMER_FINISH
import com.yaroslav.habittracker.timer.TimerScreen
import com.yaroslav.habittracker.timer.TimerViewModel
import com.yaroslav.habittracker.timer.rememberTimerStarter
import com.yaroslav.habittracker.today.TodayScreen
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.ErrorScreen
import com.yaroslav.habittracker.ui.LoadingScreen
import com.yaroslav.habittracker.ui.theme.HabitTrackerTheme
import com.yaroslav.habittracker.work.WorkViewModel
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val habits by viewModels<HabitsViewModel>()
    private val settings by viewModels<SettingsViewModel>()
    private val calendar by viewModels<CalendarViewModel>()
    private val work by viewModels<WorkViewModel>()
    private val timer by viewModels<TimerViewModel>()
    private val tasks by viewModels<TaskViewModel>()
    private val week by viewModels<WeekViewModel>()
    private val restore by viewModels<RestoreViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    habits.refreshDate()
                    val now = ZonedDateTime.now()
                    val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                    delay(Duration.between(now, midnight).toMillis().coerceIn(1L, 60_000L))
                }
            }
        }
        routeNotification(intent)
        setContent {
            HabitTrackerTheme {
                HabitTrackerApp(habits, calendar, settings, timer, tasks, week, restore, work)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        habits.refreshDate()
        timer.reconcile()
        (application as HabitTrackerApplication).refreshReminders()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeNotification(intent)
    }

    private fun routeNotification(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == OPEN_PLAN_CALENDAR) {
            val date = intent.reminderDate() ?: return
            calendar.select(date)
            habits.selectTab(Tab.Calendar)
            intent.action = null
            return
        }
        if (action != OPEN_TIMER && action != OPEN_TIMER_FINISH) return
        // External intents only select a screen and cannot control a timer.
        habits.open(Screen.Timer())
        timer.requestFromNotification(action == OPEN_TIMER_FINISH)
        intent.action = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitTrackerApp(
    habits: HabitsViewModel,
    calendar: CalendarViewModel,
    settings: SettingsViewModel,
    timer: TimerViewModel,
    tasks: TaskViewModel,
    week: WeekViewModel,
    restore: RestoreViewModel,
    work: WorkViewModel,
) {
    val dailyState by habits.habits.collectAsStateWithLifecycle()
    val active by timer.active.collectAsStateWithLifecycle()
    val startTimer = rememberTimerStarter(timer)
    val sessionCount by settings.sessionCount.collectAsStateWithLifecycle()
    val planCount by settings.planCount.collectAsStateWithLifecycle()
    val shiftCount by settings.shiftCount.collectAsStateWithLifecycle()
    val templateCount by settings.templateCount.collectAsStateWithLifecycle()
    val allHabits = (dailyState as? HabitsState.Loaded)?.habits.orEmpty()
    val screen = habits.screen
    val snackbar = remember { SnackbarHostState() }
    val notice = habits.notice?.let { stringResource(it) }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            habits.clearNotice()
        }
    }

    fun openTimer(habitId: Long? = null, plan: DayPlan? = null) {
        timer.clearFeedback()
        habits.open(Screen.Timer(habitId, plan))
    }

    val timerBar: (@Composable () -> Unit)? = active?.let { running ->
        { ActiveTimerBar(timer, running, allHabits, open = { openTimer() }) }
    }

    BackHandler(enabled = screen != Screen.Home || habits.tab != Tab.Today) {
        if (screen == Screen.Home) habits.selectTab(Tab.Today) else habits.goBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val title = when (screen) {
                Screen.Home -> null
                is Screen.HabitEditor -> if (screen.id == null) R.string.new_habit else R.string.edit_habit
                is Screen.HabitDetails -> R.string.habit
                is Screen.History -> R.string.habit_history
                is Screen.EditTime -> if (screen.sessionId == null) R.string.add_time else R.string.edit_time
                Screen.AllHabits -> R.string.all_habits
                Screen.BulkMark -> R.string.bulk_mark
                is Screen.Timer -> if (active == null && timer.saved == null) R.string.timer_new else R.string.timer
                Screen.Updates -> R.string.app_update
                Screen.Export -> R.string.export_data
                Screen.Restore -> R.string.restore_title
                Screen.Week -> R.string.week_title
            }
            if (title != null) {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(title)) },
                        navigationIcon = {
                            IconButton(onClick = habits::goBack) {
                                AppIcon(R.drawable.ic_back, description = stringResource(R.string.back))
                            }
                        },
                        actions = {
                            if (screen is Screen.HabitDetails) {
                                IconButton(onClick = { habits.open(Screen.HabitEditor(screen.id)) }) {
                                    AppIcon(R.drawable.ic_habit_edit, description = stringResource(R.string.edit_habit))
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                    if (screen !is Screen.Timer && active != null) {
                        ActiveTimerBar(timer, requireNotNull(active), allHabits, { openTimer() },
                            Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        },
        bottomBar = {
            if (screen == Screen.Home) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = habits.tab == Tab.Today,
                        onClick = { habits.selectTab(Tab.Today) },
                        icon = { AppIcon(R.drawable.ic_today) },
                        label = { Text(stringResource(R.string.today)) },
                    )
                    NavigationBarItem(
                        selected = habits.tab == Tab.Calendar,
                        onClick = { habits.selectTab(Tab.Calendar) },
                        icon = { AppIcon(R.drawable.ic_calendar) },
                        label = { Text(stringResource(R.string.work_tab)) },
                    )
                    NavigationBarItem(
                        selected = habits.tab == Tab.Tasks,
                        onClick = { habits.selectTab(Tab.Tasks) },
                        icon = { AppIcon(R.drawable.ic_check) },
                        label = { Text(stringResource(R.string.task_tab)) },
                    )
                    NavigationBarItem(
                        selected = habits.tab == Tab.Settings,
                        onClick = { habits.selectTab(Tab.Settings) },
                        icon = { AppIcon(R.drawable.ic_settings) },
                        label = { Text(stringResource(R.string.settings)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (screen) {
                Screen.Home -> when (habits.tab) {
                    Tab.Today -> WhenHabitsLoaded(dailyState, habits::retry) { state ->
                        TodayScreen(
                            state = state,
                            habits = habits,
                            calendar = calendar,
                            tasks = tasks,
                            timer = timer,
                            active = active,
                            openTimer = { openTimer() },
                            startTimer = startTimer,
                        )
                    }
                    Tab.Calendar -> {
                        val selectedDay by calendar.state.collectAsStateWithLifecycle()
                        CalendarScreen(
                            model = calendar,
                            state = selectedDay,
                            work = work,
                            activeTimer = timerBar,
                            launchTimer = { plan -> openTimer(plan.habitId, plan) },
                        )
                    }
                    Tab.Tasks -> {
                        val taskState by tasks.tasks.collectAsStateWithLifecycle()
                        TaskScreen(tasks, taskState, timerBar)
                    }
                    Tab.Settings -> SettingsScreen(
                        openUpdates = { habits.open(Screen.Updates) },
                        openExport = { habits.open(Screen.Export) },
                        openRestore = { habits.open(Screen.Restore) },
                        activeTimer = timerBar,
                    )
                }
                is Screen.HabitEditor -> {
                    if (screen.id == null) {
                        HabitEditorScreen(null, habits.writing) { name, description, days, goal ->
                            habits.saveHabit(null, name, description, days, goal)
                        }
                    } else {
                        WhenHabitLoaded(dailyState, screen.id, habits) { _, progress ->
                            HabitEditorScreen(progress.habit, habits.writing) { name, description, days, goal ->
                                habits.saveHabit(screen.id, name, description, days, goal)
                            }
                        }
                    }
                }
                is Screen.HabitDetails -> WhenHabitLoaded(dailyState, screen.id, habits) { _, progress ->
                    HabitDetailsScreen(
                        progress = progress,
                        writing = habits.writing,
                        markToday = { habits.markToday(screen.id) },
                        undoToday = { habits.setMark(screen.id, LocalDate.now(), complete = false) },
                        openTimer = { openTimer(screen.id) },
                        addTime = { habits.open(Screen.EditTime(screen.id)) },
                        history = { habits.open(Screen.History(screen.id)) },
                    )
                }
                is Screen.History -> WhenHabitLoaded(dailyState, screen.id, habits) { date, progress ->
                    HistoryScreen(progress, date, habits.writing,
                        changeMark = { day, checked, previous -> habits.setMark(screen.id, day, checked, previous) },
                        addTime = { habits.open(Screen.EditTime(screen.id)) },
                        editTime = { habits.open(Screen.EditTime(screen.id, it)) })
                }
                is Screen.EditTime -> WhenHabitLoaded(dailyState, screen.habitId, habits) { date, progress ->
                    val session = progress.sessions.firstOrNull { it.id == screen.sessionId }
                    ManualTimeScreen(progress.habit, session, date, habits.writing,
                        save = { day, duration -> habits.saveManualTime(screen.habitId, day, duration, session?.id) },
                        remove = session?.let { { habits.removeSession(screen.habitId, it.id) } })
                }
                Screen.AllHabits -> WhenHabitsLoaded(dailyState, habits::retry) { state ->
                    AllHabitsScreen(state, { habits.open(Screen.HabitEditor()) }) {
                        habits.open(Screen.HabitDetails(it))
                    }
                }
                Screen.BulkMark -> WhenHabitsLoaded(dailyState, habits::retry) { state ->
                    BulkMarkScreen(state, habits.writing) { habits.markToday(it, closeBulk = true) }
                }
                is Screen.Timer -> {
                    val activePlan by timer.activePlan.collectAsStateWithLifecycle()
                    val returnToCalendar = habits.tab == Tab.Calendar
                    TimerScreen(
                        model = timer,
                        active = active,
                        habits = allHabits,
                        initialHabit = screen.habitId,
                        initialPlan = screen.plan,
                        activePlan = activePlan,
                        returnToCalendar = returnToCalendar,
                        startTimer = startTimer,
                    ) { session ->
                        if (returnToCalendar && session.planId != null) {
                            timer.clearFeedback()
                            calendar.select(LocalDate.ofEpochDay(session.day))
                            habits.selectTab(Tab.Calendar)
                        } else {
                            if (session.planId == null) timer.clearFeedback()
                            habits.selectTab(Tab.Today)
                        }
                    }
                }
                Screen.Updates -> UpdateScreen(settings)
                Screen.Restore -> RestoreScreen(restore)
                Screen.Week -> {
                    val report by week.state.collectAsStateWithLifecycle()
                    WeekScreen(report, week)
                }
                Screen.Export -> WhenHabitsLoaded(dailyState, habits::retry) { state ->
                    ExportScreen(
                        settings, state.habits.size, state.habits.sumOf { it.marks.size.toLong() },
                        sessionCount, planCount, shiftCount, templateCount,
                    )
                }
            }
        }
    }
}

@Composable
private fun WhenHabitLoaded(
    state: HabitsState,
    id: Long,
    habits: HabitsViewModel,
    content: @Composable (LocalDate, HabitProgress) -> Unit,
) {
    WhenHabitsLoaded(state, habits::retry) { loaded ->
        val target = loaded.habits.firstOrNull { it.habit.id == id }
        if (target == null) LoadingScreen()
        else content(loaded.date, target)
    }
}

@Composable
private fun WhenHabitsLoaded(
    state: HabitsState,
    retry: () -> Unit,
    loaded: @Composable (HabitsState.Loaded) -> Unit,
) {
    when (state) {
        HabitsState.Loading -> LoadingScreen()
        HabitsState.Failed -> ErrorScreen(R.string.habits_load_error, retry)
        is HabitsState.Loaded -> loaded(state)
    }
}
