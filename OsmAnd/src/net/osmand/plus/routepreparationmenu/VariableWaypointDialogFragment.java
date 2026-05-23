package net.osmand.plus.routepreparationmenu;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.BaseOsmAndDialogFragment;
import net.osmand.plus.helpers.TargetPointsHelper;
import net.osmand.plus.routing.VariableWaypointResolver;
import net.osmand.plus.routing.VariableWaypointResolver.ResolutionResult;
import net.osmand.plus.settings.backend.ApplicationMode;

public class VariableWaypointDialogFragment extends BaseOsmAndDialogFragment {

	public static final String TAG = "VariableWaypointDialogFragment";

	private EditText queryInput;
	private TextView progressText;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.dialog_variable_waypoint, container, false);

		queryInput = view.findViewById(R.id.query_input);
		progressText = view.findViewById(R.id.progress_text);

		view.findViewById(R.id.cancel_button).setOnClickListener(v -> dismiss());
		view.findViewById(R.id.add_button).setOnClickListener(v -> addVariableWaypoint());

		if (getDialog() != null) {
			getDialog().setCanceledOnTouchOutside(true);
		}

		return view;
	}

	private void addVariableWaypoint() {
		String query = queryInput.getText().toString().trim();
		if (query.isEmpty()) {
			return;
		}

		MapActivity mapActivity = (MapActivity) getActivity();
		if (mapActivity == null) return;

		OsmandApplication app = mapActivity.getMyApplication();
		TargetPointsHelper targetPointsHelper = app.getTargetPointsHelper();

		progressText.setVisibility(View.VISIBLE);
		progressText.setText(R.string.waiting_for_route_calculation);

		new Thread(() -> {
			try {
				LatLon start = app.getRoutingHelper().getLastFixedLocation() != null ?
						new LatLon(app.getRoutingHelper().getLastFixedLocation().getLatitude(),
								app.getRoutingHelper().getLastFixedLocation().getLongitude()) :
						app.getSettings().getPointToStart();
				LatLon end = app.getRoutingHelper().getFinalLocation();

				if (start == null || end == null) {
					app.runInUIThread(() -> {
						progressText.setVisibility(View.GONE);
						app.showShortToastMessage(R.string.route_add_start_point);
					});
					return;
				}

				VariableWaypointResolver resolver = new VariableWaypointResolver(app);
				ResolutionResult result = resolver.resolveVariableWaypoint(query, start, end,
						targetPointsHelper.getIntermediatePointsLatLon());

				if (result != null) {
					//targetPointsHelper.updateVariableWaypointResolved(index, result.resolvedLocation, result.resolvedName);
                                        targetPointsHelper.navigateToPoint(result.resolvedLocation,true,targetPointsHelper.getIntermediatePoints().size());

					app.runInUIThread(() -> {
						progressText.setVisibility(View.GONE);
						dismiss();
					});
				} else {
					app.runInUIThread(() -> {
						progressText.setVisibility(View.GONE);
						app.showShortToastMessage(getString(R.string.variable_waypoint_resolve_failed, query));
					});
				}
			} catch (Exception e) {
				app.runInUIThread(() -> {
					progressText.setVisibility(View.GONE);
					app.showShortToastMessage(getString(R.string.variable_waypoint_resolve_failed, query));
				});
			}
		}).start();
	}

	public static boolean showInstance(@NonNull MapActivity mapActivity) {
		try {
			if (mapActivity.isActivityDestroyed()) {
				return false;
			}
			VariableWaypointDialogFragment fragment = new VariableWaypointDialogFragment();
			fragment.show(mapActivity.getSupportFragmentManager(), TAG);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}
}
