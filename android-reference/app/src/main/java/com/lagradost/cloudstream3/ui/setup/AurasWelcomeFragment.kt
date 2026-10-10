package com.lagradost.cloudstream3.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.explore.AurasMobileTheme
import com.lagradost.cloudstream3.ui.explore.AurasPalette

/** Optional first-run welcome. Catalog sources are always user-selected. */
class AurasWelcomeFragment : Fragment() {
    override fun onResume() {
        super.onResume()
        if (getKey<Boolean>(HAS_DONE_SETUP_KEY, false) == true &&
            findNavController().currentDestination?.id == R.id.navigation_auras_welcome
        ) {
            finishSetup()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            AurasMobileTheme {
                Box(
                    modifier = Modifier.fillMaxSize().background(AurasPalette.Canvas),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(17.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.auras_orbit_mark),
                            contentDescription = null,
                            modifier = Modifier.size(82.dp),
                        )
                        Text(
                            stringResource(R.string.auras_welcome_title),
                            color = AurasPalette.Text,
                            style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.auras_welcome_body),
                            color = AurasPalette.Muted,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.size(6.dp))
                        Button(
                            onClick = ::startConnection,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(15.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AurasPalette.Accent,
                                contentColor = AurasPalette.Ink,
                            ),
                        ) {
                            Text(
                                stringResource(R.string.auras_welcome_connect),
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            )
                        }
                        TextButton(
                            onClick = ::finishSetup,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.auras_welcome_later), color = AurasPalette.Accent)
                        }
                        TextButton(
                            onClick = { findNavController().navigate(R.id.navigation_auras_help) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.auras_welcome_help), color = AurasPalette.Muted)
                        }
                    }
                }
            }
        }
    }

    private fun startConnection() {
        // Choosing the primary setup action completes onboarding even if the user returns later.
        setKey(HAS_DONE_SETUP_KEY, true)
        findNavController().navigate(R.id.navigation_settings_extensions)
    }

    private fun finishSetup() {
        setKey(HAS_DONE_SETUP_KEY, true)
        findNavController().navigate(
            R.id.navigation_home,
            null,
            navOptions {
                popUpTo(R.id.navigation_setup_language) { inclusive = true }
            },
        )
    }
}
