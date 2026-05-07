/*
 * Copyright (c) 2023 LatentJam Project
 * PersonalizePreferenceFragment.kt is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package io.github.nikitasud.latentjam.settings.categories

import androidx.navigation.fragment.findNavController
import io.github.nikitasud.latentjam.R
import io.github.nikitasud.latentjam.settings.BasePreferenceFragment
import io.github.nikitasud.latentjam.settings.ui.WrappedDialogPreference
import io.github.nikitasud.latentjam.util.navigateSafe
import timber.log.Timber as L

/**
 * Personalization settings interface.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class PersonalizePreferenceFragment : BasePreferenceFragment(R.xml.preferences_personalize) {
    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_home_tabs)) {
            L.d("Navigating to home tab dialog")
            findNavController().navigateSafe(PersonalizePreferenceFragmentDirections.tabSettings())
        }
    }
}
