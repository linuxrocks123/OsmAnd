package net.osmand.plus.routing.cards;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.ksoichiro.android.observablescrollview.ScrollUtils;
import com.google.android.material.slider.Slider;

import net.osmand.AndroidUtils;
import net.osmand.plus.R;
import net.osmand.plus.UiUtilities;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.render.RendererRegistry;
import net.osmand.plus.routepreparationmenu.cards.BaseCard;
import net.osmand.plus.routing.RouteLineDrawInfo;
import net.osmand.plus.track.AppearanceViewHolder;
import net.osmand.plus.track.TrackAppearanceFragment.OnNeedScrollListener;

import java.util.Arrays;
import java.util.List;

public class RouteLineWidthCard extends BaseCard {

	private final static int CUSTOM_WIDTH_MIN = 1;
	private final static int CUSTOM_WIDTH_MAX = 24;

	private RouteLineDrawInfo routeLineDrawInfo;
	private OnNeedScrollListener onNeedScrollListener;

	private WidthMode selectedMode;

	private WidthAdapter widthAdapter;
	private View sliderContainer;
	private RecyclerView groupRecyclerView;
	private TextView tvModeType;
	private TextView tvDescription;

	private enum WidthMode {
		DEFAULT(R.string.map_widget_renderer, R.drawable.ic_action_map_style, null),
		THIN(R.string.rendering_value_thin_name, R.drawable.ic_action_track_line_thin_color, 5),
		MEDIUM(R.string.rendering_value_medium_name, R.drawable.ic_action_track_line_medium_color, 13),
		THICK(R.string.rendering_value_bold_name, R.drawable.ic_action_track_line_bold_color, 28),
		CUSTOM(R.string.shared_string_custom, R.drawable.ic_action_settings, null);

		WidthMode(int titleId, int iconId, Integer width) {
			this.titleId = titleId;
			this.iconId = iconId;
			this.width = width;
		}

		int titleId;
		int iconId;
		Integer width;
	}

	public RouteLineWidthCard(@NonNull MapActivity mapActivity,
	                          @NonNull RouteLineDrawInfo routeLineDrawInfo,
	                          @NonNull OnNeedScrollListener onNeedScrollListener) {
		super(mapActivity);
		this.routeLineDrawInfo = routeLineDrawInfo;
		this.onNeedScrollListener = onNeedScrollListener;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.route_line_width_card;
	}

	@Override
	protected void updateContent() {
		widthAdapter = new WidthAdapter();
		groupRecyclerView = view.findViewById(R.id.recycler_view);
		groupRecyclerView.setAdapter(widthAdapter);
		groupRecyclerView.setLayoutManager(new LinearLayoutManager(app, RecyclerView.HORIZONTAL, false));

		tvModeType = view.findViewById(R.id.width_type);
		tvDescription = view.findViewById(R.id.description);
		sliderContainer = view.findViewById(R.id.slider_container);
		AndroidUiHelper.updateVisibility(view.findViewById(R.id.top_divider), isShowDivider());

		initSelectedMode();
	}

	private void initSelectedMode() {
		selectedMode = findAppropriateMode(getRouteLineWidth());
		modeChanged();
	}
	
	private void modeChanged() {
		updateHeader();
		updateDescription();
		updateCustomWidthSlider();
		scrollMenuToSelectedItem();
	}

	public void updateItems() {
		if (widthAdapter != null) {
			widthAdapter.notifyDataSetChanged();
		}
	}

	private void setRouteLineWidth(Integer width) {
		routeLineDrawInfo.setWidth(width);
		mapActivity.refreshMap();
	}

	private Integer getRouteLineWidth() {
		return routeLineDrawInfo.getWidth();
	}

	private void updateHeader() {
		tvModeType.setText(app.getString(selectedMode.titleId));
	}

	private void updateDescription() {
		if (selectedMode == WidthMode.DEFAULT) {
			String pattern = app.getString(R.string.route_line_use_map_style_appearance);
			String width = app.getString(R.string.shared_string_color).toLowerCase();
			String description = String.format(pattern, width, RendererRegistry.getMapStyleName(app));
			tvDescription.setText(description);
			tvDescription.setVisibility(View.VISIBLE);
		} else {
			tvDescription.setVisibility(View.GONE);
		}
	}

	private void updateCustomWidthSlider() {
		if (selectedMode == WidthMode.CUSTOM) {
			Slider slider = view.findViewById(R.id.width_slider);
			final TextView tvCustomWidth = view.findViewById(R.id.width_value_tv);

			slider.setValueTo(CUSTOM_WIDTH_MAX);
			slider.setValueFrom(CUSTOM_WIDTH_MIN);

			((TextView) view.findViewById(R.id.width_value_min)).setText(String.valueOf(CUSTOM_WIDTH_MIN));
			((TextView) view.findViewById(R.id.width_value_max)).setText(String.valueOf(CUSTOM_WIDTH_MAX));

			Integer width = getRouteLineWidth();
			if (width == null || width > CUSTOM_WIDTH_MAX || width < CUSTOM_WIDTH_MIN) {
				width = CUSTOM_WIDTH_MIN;
				setRouteLineWidth(width);
			}
			tvCustomWidth.setText(String.valueOf(width));
			slider.setValue(width);

			slider.addOnChangeListener(new Slider.OnChangeListener() {
				@Override
				public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
					if (fromUser) {
						Integer newWidth = (int) value;
						setRouteLineWidth(newWidth);
						tvCustomWidth.setText(String.valueOf(newWidth));
					}
				}
			});
			UiUtilities.setupSlider(slider, nightMode, null, true);
			ScrollUtils.addOnGlobalLayoutListener(sliderContainer, new Runnable() {
				@Override
				public void run() {
					if (sliderContainer.getVisibility() == View.VISIBLE) {
						onNeedScrollListener.onVerticalScrollNeeded(sliderContainer.getBottom());
					}
				}
			});
			AndroidUiHelper.updateVisibility(sliderContainer, true);
		} else {
			AndroidUiHelper.updateVisibility(sliderContainer, false);
		}
	}

	private void scrollMenuToSelectedItem() {
		int position = widthAdapter.getItemPosition(selectedMode);
		if (position != -1) {
			groupRecyclerView.scrollToPosition(position);
		}
	}

	private static WidthMode findAppropriateMode(Integer width) {
		WidthMode result = null;
		if (width != null) {
			for (WidthMode mode : WidthMode.values()) {
				if (mode.width != null && (int) width == mode.width) {
					result = mode;
					break;
				}
			}
			if (result == null) {
				result = WidthMode.CUSTOM;
			}
		} else {
			result = WidthMode.DEFAULT;
		}
		return result;
	}

	private class WidthAdapter extends RecyclerView.Adapter<AppearanceViewHolder> {

		private final List<WidthMode> items = Arrays.asList(WidthMode.values());

		@NonNull
		@Override
		public AppearanceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater themedInflater = UiUtilities.getInflater(parent.getContext(), nightMode);
			View view = themedInflater.inflate(R.layout.point_editor_group_select_item, parent, false);
			view.getLayoutParams().width = app.getResources().getDimensionPixelSize(R.dimen.gpx_group_button_width);
			view.getLayoutParams().height = app.getResources().getDimensionPixelSize(R.dimen.gpx_group_button_height);

			AppearanceViewHolder holder = new AppearanceViewHolder(view);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				AndroidUtils.setBackground(app, holder.button, nightMode, R.drawable.ripple_solid_light_6dp,
						R.drawable.ripple_solid_dark_6dp);
			}
			return holder;
		}

		@Override
		public void onBindViewHolder(@NonNull final AppearanceViewHolder holder, int position) {
			WidthMode item = items.get(position);
			holder.title.setText(app.getString(item.titleId));

			updateButtonBg(holder, item);
			updateTextAndIconColor(holder, item);

			holder.itemView.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					int prevSelectedPosition = getItemPosition(selectedMode);
					selectedMode = items.get(holder.getAdapterPosition());
					notifyItemChanged(holder.getAdapterPosition());
					notifyItemChanged(prevSelectedPosition);

					if (selectedMode != WidthMode.CUSTOM) {
						setRouteLineWidth(selectedMode.width);
					}
					modeChanged();

					CardListener listener = getListener();
					if (listener != null) {
						listener.onCardPressed(RouteLineWidthCard.this);
					}
				}
			});
		}

		private void updateTextAndIconColor(AppearanceViewHolder holder, WidthMode item) {
			Context ctx = holder.itemView.getContext();
			int iconColor;
			int textColorId;

			if (selectedMode == item) {
				iconColor = getIconColor(item, AndroidUtils.getColorFromAttr(ctx, R.attr.default_icon_color));
				textColorId = AndroidUtils.getColorFromAttr(ctx, android.R.attr.textColor);
			} else {
				iconColor = getIconColor(item, AndroidUtils.getColorFromAttr(ctx, R.attr.colorPrimary));
				textColorId = AndroidUtils.getColorFromAttr(ctx, R.attr.colorPrimary);
			}

			holder.icon.setImageDrawable(app.getUIUtilities().getPaintedIcon(item.iconId, iconColor));
			holder.title.setTextColor(textColorId);
		}

		private int getIconColor(@NonNull WidthMode mode, @ColorInt int defaultColor) {
			return mode.width != null ? getRouteLineColor() : defaultColor;
		}

		private int getRouteLineColor() {
			Integer color = routeLineDrawInfo.getColor();
			return color != null ? color :
					mapActivity.getMapLayers().getRouteLayer().getRouteLineColor(nightMode);
		}

		private void updateButtonBg(AppearanceViewHolder holder, WidthMode item) {
			GradientDrawable rectContourDrawable = (GradientDrawable) AppCompatResources.getDrawable(app, R.drawable.bg_select_group_button_outline);
			if (rectContourDrawable != null) {
				if (selectedMode == item) {
					int strokeColor = ContextCompat.getColor(app, nightMode ? R.color.active_color_primary_dark : R.color.active_color_primary_light);
					rectContourDrawable.setStroke(AndroidUtils.dpToPx(app, 2), strokeColor);
				} else {
					int strokeColor = ContextCompat.getColor(app, nightMode ?
							R.color.stroked_buttons_and_links_outline_dark
							: R.color.stroked_buttons_and_links_outline_light);
					rectContourDrawable.setStroke(AndroidUtils.dpToPx(app, 1), strokeColor);
				}
				holder.button.setImageDrawable(rectContourDrawable);
			}
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		int getItemPosition(WidthMode widthMode) {
			return items.indexOf(widthMode);
		}
	}
}
