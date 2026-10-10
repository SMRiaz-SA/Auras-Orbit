package com.lagradost.cloudstream3.ui.setup

import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentSetupLanguageBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.explore.AurasMobileTheme
import com.lagradost.cloudstream3.ui.explore.AurasPalette
import com.lagradost.cloudstream3.ui.settings.appLanguages
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import com.lagradost.cloudstream3.ui.settings.nameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import java.util.Locale

const val HAS_DONE_SETUP_KEY = "HAS_DONE_SETUP"

private data class SetupLanguageOption(
    val tag: String,
    val englishName: String,
    val nativeName: String,
    val flag: String,
)

class SetupFragmentLanguage : BaseFragment<FragmentSetupLanguageBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentSetupLanguageBinding::inflate)
) {

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    override fun onBindingCreated(binding: FragmentSetupLanguageBinding) {
        safe {
            val ctx = context ?: return@safe
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)
            val languageOptions = appLanguages.map { (fallbackName, tag) ->
                val locale = Locale.forLanguageTag(tag)
                val englishName = locale.getDisplayName(Locale.ENGLISH)
                    .takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
                    ?: fallbackName
                val nativeName = locale.getDisplayName(locale)
                    .takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
                    ?: fallbackName

                SetupLanguageOption(
                    tag = tag,
                    englishName = englishName,
                    nativeName = nativeName,
                    flag = (fallbackName to tag).nameNextToFlagEmoji().substringBefore('\u00a0').trim(),
                )
            }
            val current = getCurrentLocale(ctx).replace('_', '-')
            val selectedIndex = languageOptions.indexOfFirst { it.tag == current }
                .takeIf { it >= 0 }
                ?: languageOptions.indexOfFirst { it.tag == current.substringBefore('-') }.coerceAtLeast(0)

            binding.languageComposeView.apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    var selectedLanguageIndex by remember { mutableIntStateOf(selectedIndex) }

                    AurasMobileTheme {
                        SetupLanguageScreen(
                            options = languageOptions,
                            selectedIndex = selectedLanguageIndex,
                            onSelectLanguage = { index ->
                                selectedLanguageIndex = index
                                val languageTag = languageOptions[index].tag
                                CommonActivity.setLocale(activity, languageTag)
                                settingsManager.edit {
                                    putString(getString(R.string.locale_key), languageTag)
                                }
                            },
                            onNext = {
                                findNavController().navigate(R.id.navigation_auras_welcome)
                            },
                            onSkip = {
                                setKey(HAS_DONE_SETUP_KEY, true)
                                findNavController().navigate(
                                    R.id.navigation_home,
                                    null,
                                    navOptions {
                                        popUpTo(R.id.navigation_setup_language) { inclusive = true }
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupLanguageScreen(
    options: List<SetupLanguageOption>,
    selectedIndex: Int,
    onSelectLanguage: (Int) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AurasPalette.Canvas)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.auras_orbit_mark),
                contentDescription = null,
                modifier = Modifier.size(31.dp),
            )
            Text(
                text = "AURAS ORBIT",
                modifier = Modifier.padding(start = 9.dp),
                color = AurasPalette.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.25.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "1 / 2",
                color = AurasPalette.Accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = stringResource(R.string.auras_setup_language_title),
            modifier = Modifier.padding(top = 15.dp),
            color = AurasPalette.Text,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.auras_setup_language_description),
            modifier = Modifier.padding(top = 3.dp),
            color = AurasPalette.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            itemsIndexed(options) { index, option ->
                SetupLanguageOptionRow(
                    option = option,
                    selected = index == selectedIndex,
                    onSelect = { onSelectLanguage(index) },
                )
            }
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AurasPalette.Accent,
                contentColor = AurasPalette.Ink,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.next), fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp),
        ) {
            Text(stringResource(R.string.skip_setup), color = AurasPalette.Accent)
        }
    }
}

@Composable
private fun SetupLanguageOptionRow(
    option: SetupLanguageOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(
                    selected = selected,
                    role = Role.Checkbox,
                    onClick = onSelect,
                )
                .padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${option.flag}  ${option.englishName}",
                    color = AurasPalette.Text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = option.nativeName,
                    modifier = Modifier.padding(top = 2.dp),
                    color = AurasPalette.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.size(48.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = AurasPalette.Accent,
                    uncheckedColor = AurasPalette.Muted.copy(alpha = 0.72f),
                    checkmarkColor = AurasPalette.Ink,
                ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AurasPalette.Stroke),
        )
    }
}
