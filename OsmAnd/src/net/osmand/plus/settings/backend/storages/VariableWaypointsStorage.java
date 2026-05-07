package net.osmand.plus.settings.backend.storages;

import net.osmand.data.LatLon;
import net.osmand.plus.helpers.TargetPointsHelper.VariableTargetPoint;
import net.osmand.plus.settings.backend.OsmandSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VariableWaypointsStorage {

	private static final String VARIABLE_WAYPOINTS_KEY = "variable_waypoints";

	private final OsmandSettings osmandSettings;

	public VariableWaypointsStorage(OsmandSettings osmandSettings) {
		this.osmandSettings = osmandSettings;
	}

	public List<VariableTargetPoint> getVariableWaypoints() {
		List<VariableTargetPoint> list = new ArrayList<>();
		String data = osmandSettings.getSettingsAPI().getString(
				osmandSettings.getGlobalPreferences(), VARIABLE_WAYPOINTS_KEY, "");
		if (data.trim().isEmpty()) {
			return list;
		}
		String[] entries = data.split("\\|\\|");
		for (String entry : entries) {
			if (entry.isEmpty()) continue;
			String[] parts = entry.split("~~");
			if (parts.length >= 3) {
				String name = parts[0];
				String query = parts[1];
				int position = Integer.parseInt(parts[2]);
				LatLon resolved = null;
				if (parts.length >= 5) {
					double lat = Double.parseDouble(parts[3]);
					double lon = Double.parseDouble(parts[4]);
					resolved = new LatLon(lat, lon);
				}
				VariableTargetPoint vp = new VariableTargetPoint(name, query, position);
				vp.setResolvedLocation(resolved);
				list.add(vp);
			}
		}
		return list;
	}

	public boolean saveVariableWaypoints(List<VariableTargetPoint> points) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < points.size(); i++) {
			if (i > 0) sb.append("||");
			VariableTargetPoint vp = points.get(i);
			sb.append(vp.getDisplayName() != null ? vp.getDisplayName() : "");
			sb.append("~~");
			sb.append(vp.getPoiQuery());
			sb.append("~~");
			sb.append(vp.getPosition());
			sb.append("~~");
			if (vp.getResolvedLocation() != null) {
				sb.append(vp.getResolvedLocation().getLatitude());
				sb.append("~~");
				sb.append(vp.getResolvedLocation().getLongitude());
			} else {
				sb.append("~~");
				sb.append("");
			}
		}
		return osmandSettings.getSettingsAPI().edit(osmandSettings.getGlobalPreferences())
				.putString(VARIABLE_WAYPOINTS_KEY, sb.toString())
				.commit();
	}

	public boolean insertVariableWaypoint(VariableTargetPoint point, int index) {
		List<VariableTargetPoint> points = getVariableWaypoints();
		points.add(index, point);
		renumberPositions(points);
		return saveVariableWaypoints(points);
	}

	public boolean deleteVariableWaypoint(int index) {
		List<VariableTargetPoint> points = getVariableWaypoints();
		if (index < 0 || index >= points.size()) return false;
		points.remove(index);
		renumberPositions(points);
		return saveVariableWaypoints(points);
	}

	public boolean clearVariableWaypoints() {
		return osmandSettings.getSettingsAPI().edit(osmandSettings.getGlobalPreferences())
				.putString(VARIABLE_WAYPOINTS_KEY, "")
				.commit();
	}

	public boolean reorderVariableWaypoints(List<VariableTargetPoint> points) {
		renumberPositions(points);
		return saveVariableWaypoints(points);
	}

	private void renumberPositions(List<VariableTargetPoint> points) {
		for (int i = 0; i < points.size(); i++) {
			points.get(i).setPosition(i);
		}
	}
}
