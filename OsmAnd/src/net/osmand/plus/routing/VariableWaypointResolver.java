package net.osmand.plus.routing;

import static net.osmand.binary.BinaryMapIndexReader.ACCEPT_ALL_POI_TYPE_FILTER;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.Amenity;
import net.osmand.data.FavouritePoint;
import net.osmand.data.LatLon;
import net.osmand.data.QuadRect;
import net.osmand.osm.AbstractPoiType;
import net.osmand.osm.MapPoiTypes;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.TargetPointsHelper.VariableTargetPoint;
import net.osmand.plus.myplaces.favorites.FavouritesHelper;
import net.osmand.plus.search.QuickSearchHelper;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class VariableWaypointResolver {

	private static final Log log = PlatformUtil.getLog(VariableWaypointResolver.class);

	private static final int MAX_CANDIDATES = 50;
	private static final int SEARCH_RADIUS_METERS = 10000;

	private final OsmandApplication app;

	public VariableWaypointResolver(OsmandApplication app) {
		this.app = app;
	}

	public static class ResolutionResult {
		public final LatLon resolvedLocation;
		public final String resolvedName;
		public final long estimatedDetourTimeSeconds;

		public ResolutionResult(LatLon resolvedLocation, String resolvedName,
		                        long estimatedDetourTimeSeconds) {
			this.resolvedLocation = resolvedLocation;
			this.resolvedName = resolvedName;
			this.estimatedDetourTimeSeconds = estimatedDetourTimeSeconds;
		}
	}

	@Nullable
	public ResolutionResult resolveVariableWaypoint(@NonNull VariableTargetPoint variableWaypoint,
	                                                @NonNull LatLon startPoint,
	                                                @NonNull LatLon endPoint,
	                                                @NonNull List<LatLon> currentIntermediates) {
		String query = variableWaypoint.getPoiQuery();
		if (query == null || query.trim().isEmpty()) {
			return null;
		}

		List<CandidatePOI> candidates = findCandidatePOIs(query, startPoint, endPoint, currentIntermediates);
		if (candidates.isEmpty()) {
			log.info("No candidate POIs found for query: " + query);
			return null;
		}

		Collections.sort(candidates, new Comparator<CandidatePOI>() {
			@Override
			public int compare(CandidatePOI a, CandidatePOI b) {
				return Double.compare(a.detourTimeSeconds, b.detourTimeSeconds);
			}
		});

		CandidatePOI best = candidates.get(0);
		String name = getCandidateName(best);

		log.info("Resolved variable waypoint '" + query + "' to: " + name +
				" at (" + best.location.getLatitude() + ", " + best.location.getLongitude() +
				") with detour time: " + best.detourTimeSeconds + "s");

		return new ResolutionResult(best.location, name, (long) best.detourTimeSeconds);
	}

	private List<CandidatePOI> findCandidatePOIs(String query, LatLon startPoint, LatLon endPoint,
	                                             List<LatLon> currentIntermediates) {
		List<CandidatePOI> candidates = new ArrayList<>();

		double maxDist = MapUtils.getDistance(startPoint, endPoint);
		double searchRadius = Math.max(SEARCH_RADIUS_METERS, maxDist * 0.6);

		double centerLat = (startPoint.getLatitude() + endPoint.getLatitude()) / 2.0;
		double centerLon = (startPoint.getLongitude() + endPoint.getLongitude()) / 2.0;

		double latSpan = searchRadius / 111000.0;
		double lonSpan = searchRadius / (111000.0 * Math.cos(Math.toRadians(centerLat)));

		QuadRect bbox = new QuadRect(
				centerLon - lonSpan, centerLat + latSpan,
				centerLon + lonSpan, centerLat - latSpan
		);

		List<Amenity> amenities = app.getResourceManager().searchAmenities(ACCEPT_ALL_POI_TYPE_FILTER, bbox, true);
		for (Amenity amenity : amenities) {
			if (matchesQuery(amenity, query)) {
				LatLon poiLocation = amenity.getLocation();
				if (isNearRoute(poiLocation, startPoint, endPoint, currentIntermediates)) {
					candidates.add(new CandidatePOI(amenity, poiLocation));
				}
			}
			if (candidates.size() >= MAX_CANDIDATES * 2) {
				break;
			}
		}

		searchFavoriteCandidates(query, startPoint, endPoint, currentIntermediates, candidates);

		QuickSearchHelper searchHelper = app.getSearchUICore();
		if (searchHelper != null && candidates.size() < MAX_CANDIDATES) {
			Amenity amenity = searchHelper.findAmenity(query, centerLat, centerLon, null, false);
			if (amenity != null) {
				LatLon poiLocation = amenity.getLocation();
				if (isNearRoute(poiLocation, startPoint, endPoint, currentIntermediates)) {
					boolean alreadyAdded = false;
					for (CandidatePOI c : candidates) {
						if (MapUtils.getDistance(c.location, poiLocation) < 10) {
							alreadyAdded = true;
							break;
						}
					}
					if (!alreadyAdded) {
						candidates.add(new CandidatePOI(amenity, poiLocation));
					}
				}
			}
		}

		if (candidates.isEmpty()) {
			candidates.addAll(searchPOIsOnline(query, centerLat, centerLon, startPoint, endPoint, currentIntermediates));
		}

		if (candidates.isEmpty()) {
			candidates = findCandidatesByDistance(query, startPoint, endPoint, currentIntermediates);
		}

		calculateDetourTimes(candidates, startPoint, endPoint, currentIntermediates);

		if (candidates.size() > MAX_CANDIDATES) {
			return candidates.subList(0, MAX_CANDIDATES);
		}
		return candidates;
	}

	private List<CandidatePOI> findCandidatesByDistance(String query, LatLon startPoint, LatLon endPoint,
	                                                    List<LatLon> currentIntermediates) {
		List<CandidatePOI> candidates = new ArrayList<>();

		List<LatLon> routePoints = new ArrayList<>();
		routePoints.add(startPoint);
		routePoints.addAll(currentIntermediates);
		routePoints.add(endPoint);

		for (int i = 0; i < routePoints.size() - 1; i++) {
			LatLon segStart = routePoints.get(i);
			LatLon segEnd = routePoints.get(i + 1);
			double segDist = MapUtils.getDistance(segStart, segEnd);
			int numSamples = (int) Math.max(3, segDist / 2000.0);

			for (int s = 0; s <= numSamples; s++) {
				double t = (double) s / numSamples;
				LatLon samplePoint = new LatLon(
						segStart.getLatitude() + t * (segEnd.getLatitude() - segStart.getLatitude()),
						segStart.getLongitude() + t * (segEnd.getLongitude() - segStart.getLongitude())
				);

				double sampleSearchRadius = Math.max(SEARCH_RADIUS_METERS, segDist * 0.3);
				double latSpan = sampleSearchRadius / 111000.0;
				double lonSpan = sampleSearchRadius / (111000.0 * Math.cos(Math.toRadians(samplePoint.getLatitude())));

				QuadRect bbox = new QuadRect(
						samplePoint.getLongitude() - lonSpan, samplePoint.getLatitude() + latSpan,
						samplePoint.getLongitude() + lonSpan, samplePoint.getLatitude() - latSpan
				);

				List<Amenity> amenities = app.getResourceManager().searchAmenities(ACCEPT_ALL_POI_TYPE_FILTER, bbox, true);
				for (Amenity amenity : amenities) {
					if (matchesQuery(amenity, query)) {
						LatLon poiLocation = amenity.getLocation();
						boolean alreadyAdded = false;
						for (CandidatePOI c : candidates) {
							if (MapUtils.getDistance(c.location, poiLocation) < 50) {
								alreadyAdded = true;
								break;
							}
						}
						if (!alreadyAdded) {
							candidates.add(new CandidatePOI(amenity, poiLocation));
						}
					}
				}

				FavouritesHelper favouritesHelper = app.getFavoritesHelper();
				if (favouritesHelper != null) {
					for (FavouritePoint fp : favouritesHelper.getFavouritePoints()) {
						if (!fp.isVisible()) continue;
						if (matchesFavouriteQuery(fp, query)) {
							LatLon favLocation = new LatLon(fp.getLatitude(), fp.getLongitude());
							boolean alreadyAdded = false;
							for (CandidatePOI c : candidates) {
								if (MapUtils.getDistance(c.location, favLocation) < 50) {
									alreadyAdded = true;
									break;
								}
							}
							if (!alreadyAdded) {
								candidates.add(new CandidatePOI(fp, favLocation));
							}
						}
					}
				}
				if (candidates.size() >= MAX_CANDIDATES * 2) {
					break;
				}
			}
			if (candidates.size() >= MAX_CANDIDATES * 2) {
				break;
			}
		}
		return candidates;
	}

	private void searchFavoriteCandidates(String query, LatLon startPoint, LatLon endPoint,
	                                      List<LatLon> currentIntermediates, List<CandidatePOI> candidates) {
		FavouritesHelper favouritesHelper = app.getFavoritesHelper();
		if (favouritesHelper == null) return;

		List<FavouritePoint> favourites = favouritesHelper.getFavouritePoints();
		for (FavouritePoint fp : favourites) {
			if (!fp.isVisible()) continue;
			if (matchesFavouriteQuery(fp, query)) {
				LatLon favLocation = new LatLon(fp.getLatitude(), fp.getLongitude());
				if (isNearRoute(favLocation, startPoint, endPoint, currentIntermediates)) {
					boolean alreadyAdded = false;
					for (CandidatePOI c : candidates) {
						if (MapUtils.getDistance(c.location, favLocation) < 10) {
							alreadyAdded = true;
							break;
						}
					}
					if (!alreadyAdded) {
						candidates.add(new CandidatePOI(fp, favLocation));
					}
				}
			}
			if (candidates.size() >= MAX_CANDIDATES * 2) {
				break;
			}
		}
	}

	private boolean matchesFavouriteQuery(FavouritePoint point, String query) {
		String q = query.toLowerCase();
		String name = point.getName();
		if (name != null && !name.isEmpty() && name.toLowerCase().contains(q)) {
			return true;
		}
		String category = point.getCategory();
		if (category != null && category.toLowerCase().contains(q)) {
			return true;
		}
		return false;
	}

	private List<CandidatePOI> searchPOIsOnline(String query, double centerLat, double centerLon,
	                                            LatLon startPoint, LatLon endPoint,
	                                            List<LatLon> currentIntermediates) {
		List<CandidatePOI> candidates = new ArrayList<>();
		try {
			double radiusKm = Math.max(10, MapUtils.getDistance(startPoint, endPoint) / 1000.0 * 0.5);
			String url = "https://nominatim.openstreetmap.org/search" +
					"?q=" + URLEncoder.encode(query, "UTF-8") +
					"&lat=" + centerLat + "&lon=" + centerLon +
					"&radius=" + radiusKm +
					"&format=json&limit=" + MAX_CANDIDATES;

			HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setRequestProperty("User-Agent", "OsmAnd/4.0");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(10000);

			if (conn.getResponseCode() == 200) {
				InputStream is = conn.getInputStream();
				String json = new BufferedReader(new InputStreamReader(is, "UTF-8")).readLine();
				if (json != null) {
					org.json.JSONArray jsonArray = new org.json.JSONArray(json);
					for (int i = 0; i < jsonArray.length(); i++) {
						org.json.JSONObject obj = jsonArray.getJSONObject(i);
						double lat = obj.getDouble("lat");
						double lon = obj.getDouble("lon");
						String name = obj.optString("display_name", query);
						LatLon location = new LatLon(lat, lon);

						if (isNearRoute(location, startPoint, endPoint, currentIntermediates)) {
							Amenity amenity = createAmenityFromNominatim(name, lat, lon);
							candidates.add(new CandidatePOI(amenity, location));
						}
					}
				}
			}
		} catch (Exception e) {
			log.error("Error searching POIs online via Nominatim", e);
		}
		return candidates;
	}

	private Amenity createAmenityFromNominatim(String name, double lat, double lon) {
		Amenity amenity = new Amenity();
		amenity.setLocation(lat, lon);
		amenity.setName(name);
		amenity.setSubType("search_result");
		return amenity;
	}

	private boolean matchesQuery(Amenity amenity, String query) {
		String name = amenity.getName();
		if (name != null && !name.isEmpty() && name.toLowerCase().contains(query.toLowerCase())) {
			return true;
		}
		String subType = amenity.getSubType();
		if (subType != null && subType.toLowerCase().contains(query.toLowerCase())) {
			return true;
		}
		MapPoiTypes poiTypes = app.getPoiTypes();
		if (poiTypes != null && subType != null) {
			AbstractPoiType type = poiTypes.getAnyPoiTypeByKey(subType);
			if (type != null && type.getTranslation() != null &&
					type.getTranslation().toLowerCase().contains(query.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	private boolean isNearRoute(LatLon poi, LatLon start, LatLon end, List<LatLon> intermediates) {
		List<LatLon> routePoints = new ArrayList<>();
		routePoints.add(start);
		routePoints.addAll(intermediates);
		routePoints.add(end);

		for (int i = 0; i < routePoints.size() - 1; i++) {
			double dist = distancePointToSegment(poi, routePoints.get(i), routePoints.get(i + 1));
			if (dist < SEARCH_RADIUS_METERS * 1.5) {
				return true;
			}
		}
		return false;
	}

	private double distancePointToSegment(LatLon point, LatLon segStart, LatLon segEnd) {
		double segLength = MapUtils.getDistance(segStart, segEnd);
		if (segLength < 1) return MapUtils.getDistance(point, segStart);

		double dx = segEnd.getLatitude() - segStart.getLatitude();
		double dy = segEnd.getLongitude() - segStart.getLongitude();
		double lenSq = dx * dx + dy * dy;

		double t = ((point.getLatitude() - segStart.getLatitude()) * dx +
				(point.getLongitude() - segStart.getLongitude()) * dy) / lenSq;

		t = Math.max(0, Math.min(1, t));

		LatLon projection = new LatLon(
				segStart.getLatitude() + t * dx,
				segStart.getLongitude() + t * dy
		);
		return MapUtils.getDistance(point, projection);
	}

	private void calculateDetourTimes(List<CandidatePOI> candidates, LatLon start, LatLon end,
	                                  List<LatLon> currentIntermediates) {
		double baseTime = estimateRouteTime(start, end, currentIntermediates);

		for (CandidatePOI candidate : candidates) {
			double bestInsertionTime = Double.MAX_VALUE;

			for (int i = 0; i <= currentIntermediates.size(); i++) {
				List<LatLon> withInsertion = new ArrayList<>(currentIntermediates);
				withInsertion.add(i, candidate.location);

				double timeWithInsertion = estimateRouteTime(start, end, withInsertion);
				if (timeWithInsertion < bestInsertionTime) {
					bestInsertionTime = timeWithInsertion;
				}
			}

			candidate.detourTimeSeconds = bestInsertionTime - baseTime;
			if (candidate.detourTimeSeconds < 0) candidate.detourTimeSeconds = 0;
		}
	}

	private double estimateRouteTime(LatLon start, LatLon end, List<LatLon> intermediates) {
		RouteProvider provider = app.getRoutingHelper().getProvider();
		ApplicationMode mode = app.getRoutingHelper().getAppMode();

		List<LatLon> allPoints = new ArrayList<>();
		allPoints.add(start);
		allPoints.addAll(intermediates);
		allPoints.add(end);

		double totalTime = 0;
		for (int i = 0; i < allPoints.size() - 1; i++) {
			Location startLoc = new Location("estimate");
			startLoc.setLatitude(allPoints.get(i).getLatitude());
			startLoc.setLongitude(allPoints.get(i).getLongitude());

			RouteCalculationParams params = new RouteCalculationParams();
			params.start = startLoc;
			params.end = allPoints.get(i + 1);
			params.intermediates = new ArrayList<>();
			params.ctx = app;
			params.mode = mode;
			params.fast = true;
			params.calculationProgress = new RouteCalculationProgress();

			try {
				RouteCalculationResult result = provider.calculateRouteImpl(params);
				if (result != null && result.isCalculated()) {
					totalTime += result.getRoutingTime();
				} else {
					totalTime += straightLineTime(allPoints.get(i), allPoints.get(i + 1), mode);
				}
			} catch (Exception e) {
				totalTime += straightLineTime(allPoints.get(i), allPoints.get(i + 1), mode);
			}
		}
		return totalTime;
	}

	private double straightLineTime(LatLon a, LatLon b, ApplicationMode mode) {
		double distance = MapUtils.getDistance(a, b);
		double speedMs = mode.getDefaultSpeed() / 3.6;
		return distance / speedMs;
	}

	private String getCandidateName(CandidatePOI candidate) {
		if (candidate.favouritePoint != null) {
			String name = candidate.favouritePoint.getName();
			if (name != null && !name.isEmpty()) {
				return name;
			}
			return candidate.favouritePoint.getCategory();
		}
		return getPoiName(candidate.amenity);
	}

	private String getPoiName(Amenity amenity) {
		String name = amenity.getName();
		if (name != null && !name.isEmpty()) {
			return name;
		}
		MapPoiTypes poiTypes = app.getPoiTypes();
		if (poiTypes != null && amenity.getSubType() != null) {
			AbstractPoiType type = poiTypes.getAnyPoiTypeByKey(amenity.getSubType());
			if (type != null && type.getTranslation() != null) {
				return type.getTranslation();
			}
		}
		return amenity.getSubType();
	}

	private static class CandidatePOI {
		final Amenity amenity;
		final FavouritePoint favouritePoint;
		final LatLon location;
		double detourTimeSeconds;

		CandidatePOI(Amenity amenity, LatLon location) {
			this.amenity = amenity;
			this.favouritePoint = null;
			this.location = location;
			this.detourTimeSeconds = Double.MAX_VALUE;
		}

		CandidatePOI(FavouritePoint favouritePoint, LatLon location) {
			this.amenity = null;
			this.favouritePoint = favouritePoint;
			this.location = location;
			this.detourTimeSeconds = Double.MAX_VALUE;
		}
	}
}
