package com.yaroslav.habittracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
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
import com.yaroslav.habittracker.habits.CreateHabitScreen
import com.yaroslav.habittracker.habits.HabitDetailsScreen
import com.yaroslav.habittracker.habits.HabitsState
import com.yaroslav.habittracker.habits.HabitsViewModel
import com.yaroslav.habittracker.habits.Screen
import com.yaroslav.habittracker.habits.Tab
import com.yaroslav.habittracker.habits.TodayScreen
import com.yaroslav.habittracker.settings.ExportScreen
import com.yaroslav.habittracker.settings.SettingsScreen
import com.yaroslav.habittracker.settings.SettingsViewModel
import com.yaroslav.habittracker.settings.UpdateScreen
import com.yaroslav.habittracker.ui.AppIcon
import com.yaroslav.habittracker.ui.ErrorScreen
import com.yaroslav.habittracker.ui.LoadingScreen
import com.yaroslav.habittracker.ui.theme.HabitTrackerTheme
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val habits by viewModels<HabitsViewModel>()
    private val settings by viewModels<SettingsViewModel>()

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
        setContent {
            HabitTrackerTheme {
                HabitTrackerApp(habits, settings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        habits.refreshDate()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitTrackerApp(habits: HabitsViewModel, settings: SettingsViewModel) {
    val dailyState by habits.habits.collectAsStateWithLifecycle()
    val screen = habits.screen
    val snackbar = remember { SnackbarHostState() }
    val notice = habits.notice?.let { stringResource(it) }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            habits.clearNotice()
        }
    }

    BackHandler(enabled = screen != Screen.Home || habits.tab == Tab.Settings) {
        if (screen == Screen.Home) habits.selectTab(Tab.Today) else habits.goBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val title = when (screen) {
                Screen.Home -> null
                Screen.CreateHabit -> R.string.new_habit
                is Screen.HabitDetails -> R.string.habit
                Screen.Updates -> R.string.app_update
                Screen.Export -> R.string.export_data
            }
            if (title != null) {
                TopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = {
                        IconButton(onClick = habits::goBack) {
                            AppIcon(R.drawable.ic_back, description = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
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
                            create = { habits.open(Screen.CreateHabit) },
                            open = { habits.open(Screen.HabitDetails(it)) },
                        )
                    }
                    Tab.Settings -> SettingsScreen(
                        openUpdates = { habits.open(Screen.Updates) },
                        openExport = { habits.open(Screen.Export) },
                    )
                }
                Screen.CreateHabit -> CreateHabitScreen(habits.creating, habits::createHabit)
                is Screen.HabitDetails -> WhenHabitsLoaded(dailyState, habits::retry) { state ->
                    val selected = state.habits.find { it.habit.id == screen.id }
                    if (selected == null) {
                        LoadingScreen()
                    } else {
                        HabitDetailsScreen(selected, habits.markingHabitId != null) {
                            habits.markToday(screen.id)
                        }
                    }
                }
                Screen.Updates -> UpdateScreen(settings)
                Screen.Export -> WhenHabitsLoaded(dailyState, habits::retry) { state ->
                    ExportScreen(settings, state.habits.size, state.habits.sumOf { it.completedDays.toLong() })
                }
            }
        }
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
