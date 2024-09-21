/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.ground.ui.datacollection.tasks.polygon

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.google.android.ground.ui.common.AbstractMapFragmentWithControls
import com.google.android.ground.ui.common.BaseMapViewModel
import com.google.android.ground.ui.datacollection.DataCollectionFragment
import com.google.android.ground.ui.map.CameraPosition
import com.google.android.ground.ui.map.Feature
import com.google.android.ground.ui.map.MapFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DrawAreaTaskMapFragment : AbstractMapFragmentWithControls() {

  private lateinit var mapViewModel: BaseMapViewModel
  private lateinit var viewModel: DrawAreaTaskViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    mapViewModel = getViewModel(BaseMapViewModel::class.java)
    arguments?.let {
      val taskId = it.getString("taskId")
      taskId?.let {
        viewModel =
          (requireParentFragment() as DataCollectionFragment).viewModel.getTaskViewModel(taskId)
            as DrawAreaTaskViewModel
      }
    }
  }

  override fun getMapViewModel(): BaseMapViewModel = mapViewModel

  override fun onMapReady(map: MapFragment) {
    viewLifecycleOwner.lifecycleScope.launch {
      if (this@DrawAreaTaskMapFragment::viewModel.isInitialized) {
        viewModel.draftArea.collect { feature: Feature? ->
          map.setFeatures(if (feature == null) setOf() else setOf(feature))
        }
      }
    }
  }

  override fun onMapCameraMoved(position: CameraPosition) {
    super.onMapCameraMoved(position)
    if (this@DrawAreaTaskMapFragment::viewModel.isInitialized && !viewModel.isMarkedComplete()) {
      val mapCenter = position.coordinates
      viewModel.updateLastVertexAndMaybeCompletePolygon(mapCenter) { c1, c2 ->
        map.getDistanceInPixels(c1, c2)
      }
    }
  }

  companion object {
    fun newInstance(map: MapFragment, taskId: String): DrawAreaTaskMapFragment {
      val fragment = DrawAreaTaskMapFragment().apply { this.map = map }
      val args = Bundle().apply { putString("taskId", taskId) }
      fragment.arguments = args
      return fragment
    }
  }
}
