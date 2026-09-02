package com.mishiranu.dashchan.ui.preference;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanManager;
import chan.content.ChanPerformer;
import chan.content.ExtensionException;
import chan.content.InvalidResponseException;
import chan.http.HttpClient;
import chan.http.HttpException;
import chan.http.HttpHolder;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.chan.pikabu.PikabuChanConfiguration;
import com.mishiranu.dashchan.chan.zchan.ZchanChanConfiguration;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.HttpHolderTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.database.ChanDatabase;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.CheckPreference;
import com.mishiranu.dashchan.ui.preference.core.MultipleEditPreference;
import com.mishiranu.dashchan.ui.preference.core.Preference;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.util.WebViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.ProgressDialog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class ChanFragment extends PreferenceFragment implements FragmentHandler.Callback {
	private static final String EXTRA_CHAN_NAME = "chanName";

	private Preference<List<String>> captchaPassPreference;
	private Preference<List<String>> userAuthorizationPreference;
	private Preference<?> pikabuAuthorizationPreference;
	private Preference<?> pikabuLogoutPreference;
	private Preference<?> cookiePreference;

	private static final String VALUE_CUSTOM_DOMAIN = "custom_domain\n";
	private static final String EXTRA_ANOTHER_DOMAIN_MODE = "anotherDomainMode";

	private boolean anotherDomainMode = false;

	public ChanFragment() {}

	public ChanFragment(String chanName) {
		Bundle args = new Bundle();
		args.putString(EXTRA_CHAN_NAME, chanName);
		setArguments(args);
	}

	private String getChanName() {
		return requireArguments().getString(EXTRA_CHAN_NAME);
	}

	@Override
	protected SharedPreferences getPreferences() {
		return Preferences.PREFERENCES;
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		String chanName = getChanName();
		Chan chan = Chan.get(chanName);
		ChanConfiguration.Board board = chan.configuration.safe().obtainBoard(null);
		ChanConfiguration.Deleting deleting = board.allowDeleting
				? chan.configuration.safe().obtainDeleting(null) : null;

		if (!chan.configuration.getOption(ChanConfiguration.OPTION_SINGLE_BOARD_MODE)) {
			addEdit(Preferences.KEY_DEFAULT_BOARD_NAME.bind(chanName), null,
					R.string.default_starting_board, p -> {
						String text = p.getValue();
						if (!StringUtils.isEmpty(text)) {
							String boardName = StringUtils.validateBoardName(text);
							if (boardName != null) {
								text = Chan.get(chanName).configuration.formatBoardTitle(boardName);
							} else {
								text = null;
							}
						}
						return text;
					}, null, InputType.TYPE_CLASS_TEXT);
		}
		if (board.allowCatalog) {
			addCheck(true, Preferences.KEY_LOAD_CATALOG.bind(chanName), Preferences.DEFAULT_LOAD_CATALOG,
					R.string.load_catalog, R.string.load_catalog__summary);
		}
		if (chan.configuration.getOption(ChanConfiguration.OPTION_AI_POSTING)) {
			addHeader(R.string.ai_settings);
			addCheck(true, Preferences.KEY_HIDE_AI_POSTS.bind(chanName), Preferences.DEFAULT_HIDE_AI_POSTS,
					R.string.hide_ai_posts, 0);
			addCheck(true, Preferences.KEY_CLASSIC_MONKEY_RESPONSES.bind(chanName),
					Preferences.DEFAULT_CLASSIC_MONKEY_RESPONSES, R.string.classic_monkey_responses,
					R.string.classic_monkey_responses__summary);
		}
		if (deleting != null && deleting.password) {
			Preferences.getPassword(chan); // Ensure password existence
			addEdit(Preferences.KEY_PASSWORD.bind(chanName), null,
					R.string.password_for_removal, R.string.password_for_removal__summary,
					getString(R.string.password), InputType.TYPE_CLASS_TEXT |
					InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
					.setOnAfterChangeListener(p -> {
						String value = p.getValue();
						if (StringUtils.isEmpty(value)) {
							p.setValue(Preferences.getPassword(Chan.get(chanName)));
							ClickableToast.show(R.string.new_password_was_generated);
						}
					});
		}
		Collection<String> captchaTypes = chan.configuration.getSupportedCaptchaTypes();
		if (captchaTypes != null && captchaTypes.size() > 1) {
			addList(Preferences.KEY_CAPTCHA.bind(chanName), Preferences.getCaptchaTypeValues(captchaTypes),
					Preferences.getCaptchaTypeDefaultValue(chan), R.string.captcha_type,
					Preferences.getCaptchaTypeEntries(chan, captchaTypes));
		}
		if (chan.configuration.getOption(ChanConfiguration.OPTION_ALLOW_CAPTCHA_PASS)) {
			ChanConfiguration.Authorization authorization = chan.configuration.safe().obtainCaptchaPass();
			if (authorization != null && authorization.fieldsCount > 0) {
				captchaPassPreference = addMultipleEdit(Preferences.KEY_CAPTCHA_PASS.bind(chanName),
						"ejchan".equals(chanName) ? R.string.ejchan_epass : R.string.captcha_pass,
						"ejchan".equals(chanName) ? R.string.ejchan_epass__summary
								: R.string.captcha_pass__summary,
						authorization.hints != null ? Arrays.asList(authorization.hints) : null,
						createInputTypes(authorization.fieldsCount,
								InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
						new MultipleEditPreference.ListValueCodec(authorization.fieldsCount));
				captchaPassPreference.setOnAfterChangeListener(p -> {
					List<String> values = p.getValue();
					if (Preferences.checkHasMultipleValues(values)) {
						AuthorizationDialog dialog = new AuthorizationDialog(getChanName(),
								AuthorizationType.CAPTCHA_PASS, values);
						dialog.show(getChildFragmentManager(), AuthorizationDialog.class.getName());
					}
				});
			}
		}
		if (chan.configuration.getOption(ChanConfiguration.OPTION_ALLOW_USER_AUTHORIZATION)
				&& !(chan.configuration instanceof PikabuChanConfiguration)) {
			ChanConfiguration.Authorization authorization = chan.configuration.safe().obtainUserAuthorization();
			if (authorization != null && authorization.fieldsCount > 0) {
				userAuthorizationPreference = addMultipleEdit(Preferences.KEY_USER_AUTHORIZATION.bind(chanName),
						R.string.user_authorization, 0,
						authorization.hints != null ? Arrays.asList(authorization.hints) : null,
						createInputTypes(authorization.fieldsCount,
								InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
						new MultipleEditPreference.ListValueCodec(authorization.fieldsCount));
				userAuthorizationPreference.setOnAfterChangeListener(p -> {
					List<String> values = p.getValue();
					if (Preferences.checkHasMultipleValues(values)) {
						AuthorizationDialog dialog = new AuthorizationDialog(getChanName(),
								AuthorizationType.USER, values);
						dialog.show(getChildFragmentManager(), AuthorizationDialog.class.getName());
					}
				});
			}
		}
		if (chan.configuration instanceof PikabuChanConfiguration) {
			addPikabuAuthorizationPreferences((PikabuChanConfiguration) chan.configuration);
		}
		LinkedHashMap<String, Boolean> customPreferences = chan.configuration.getCustomPreferences();
		if (customPreferences != null) {
			for (LinkedHashMap.Entry<String, Boolean> preferenceHolder : customPreferences.entrySet()) {
				String key = preferenceHolder.getKey();
				boolean defaultValue = preferenceHolder.getValue();
				ChanConfiguration.CustomPreference customPreference =
						chan.configuration.safe().obtainCustomPreference(key);
				if (customPreference != null && customPreference.title != null) {
					CheckPreference preference = addCheck(false, key, defaultValue,
							customPreference.title, customPreference.summary);
					preference.setValue(chan.configuration.get(null, key, defaultValue));
					preference.setOnAfterChangeListener(p -> {
						Chan callbackChan = Chan.get(chanName);
						callbackChan.configuration.set(null, preference.key, p.getValue());
						callbackChan.configuration.commit();
					});
				}
			}
		}
		if (chan.configuration instanceof ZchanChanConfiguration) {
			addZchanIdentityPreferences((ZchanChanConfiguration) chan.configuration);
		}
		cookiePreference = addButton(R.string.manage_cookies, 0);
		cookiePreference.setOnClickListener(p -> ((FragmentHandler) requireActivity())
				.pushFragment(new CookiesFragment(chanName)));

		ArrayList<String> domains = chan.locator.getChanHosts(true);
		boolean localMode = chan.configuration.getOption(ChanConfiguration.OPTION_LOCAL_MODE) || domains.isEmpty();
		boolean httpsConfigurable = chan.locator.isHttpsConfigurable();
		boolean canReadThreadPartially = chan.configuration.getOption(ChanConfiguration.OPTION_READ_THREAD_PARTIALLY);
		if (!localMode || httpsConfigurable || canReadThreadPartially) {
			addHeader(R.string.connection);
		}
		if (!localMode) {
			anotherDomainMode = !domains.contains(chan.locator.getConfiguredHost()) || domains.size() == 1 ||
					savedInstanceState != null && savedInstanceState.getBoolean(EXTRA_ANOTHER_DOMAIN_MODE);
			if (anotherDomainMode) {
				addAnotherDomainPreference(domains.get(0));
			} else {
				ArrayList<CharSequence> entries = new ArrayList<>(domains);
				entries.add(getString(R.string.another));
				ArrayList<String> values = new ArrayList<>(domains);
				values.add(VALUE_CUSTOM_DOMAIN);
				values.set(0, "");
				Preference<String> domainPreference = addList(Preferences.KEY_DOMAIN.bind(chanName), values,
						values.get(0), R.string.domain_name, entries);
				domainPreference.setOnBeforeChangeListener((preference, value) -> {
					if (VALUE_CUSTOM_DOMAIN.equals(value)) {
						anotherDomainMode = true;
						Preference<String> newDomainPreference = addAnotherDomainPreference(domains.get(0));
						movePreference(newDomainPreference, domainPreference);
						removePreference(domainPreference);
						newDomainPreference.performClick();
						return false;
					}
					return true;
				});
			}
		}
		if (httpsConfigurable) {
			addCheck(true, Preferences.KEY_USE_HTTPS.bind(chanName), Preferences.DEFAULT_USE_HTTPS,
					R.string.secure_connection, R.string.secure_connection__summary);
		}
		if (!localMode) {
			MultipleEditPreference<Map<String, String>> proxyPreference = addMultipleEdit
					(Preferences.KEY_PROXY.bind(chanName), R.string.proxy, "%s:%s",
							Arrays.asList(getString(R.string.address), getString(R.string.port), null),
							Arrays.asList(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
									InputType.TYPE_CLASS_NUMBER | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0),
							new MultipleEditPreference.MapValueCodec(Preferences.KEYS_PROXY));
			proxyPreference.setValues(Preferences.KEYS_PROXY.indexOf(Preferences.SUB_KEY_PROXY_TYPE),
					Preferences.ENTRIES_PROXY_TYPE, Preferences.VALUES_PROXY_TYPE);
			proxyPreference.setOnAfterChangeListener(p -> {
				boolean success = HttpClient.getInstance().checkProxyValid(p.getValue());
				if (!success) {
					ClickableToast.show(R.string.enter_valid_data);
					proxyPreference.performClick();
				}
			});
		}
		if (canReadThreadPartially) {
			addCheck(true, Preferences.KEY_PARTIAL_THREAD_LOADING.bind(chanName),
					Preferences.DEFAULT_PARTIAL_THREAD_LOADING, R.string.partial_thread_loading,
					R.string.partial_thread_loading__summary);
		}

		if (!ChanManager.getInstance().isBuiltInChan(chanName)) {
			addHeader(R.string.additional);
			addButton(R.string.uninstall_extension, 0).setOnClickListener(p -> {
				Chan innerChan = Chan.get(chanName);
				if (innerChan.name != null) {
					@SuppressWarnings("deprecation")
					Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE)
							.setData(Uri.parse("package:" + innerChan.packageName))
							.putExtra(Intent.EXTRA_RETURN_RESULT, true);
					startActivity(intent);
				}
			});
		}
	}

	private void addZchanIdentityPreferences(ZchanChanConfiguration configuration) {
		addHeader(R.string.zchan_posting_identity);
		MultipleEditPreference<List<String>> identityPreference = new MultipleEditPreference<>(requireContext(),
				"zchan_identity_profile", getString(R.string.zchan_identity_profile),
				p -> getString(R.string.zchan_identity_profile__summary),
				Arrays.asList(getString(R.string.zchan_identity_uuid), getString(R.string.zchan_identity_device_name),
						getString(R.string.zchan_identity_brand), getString(R.string.zchan_identity_model),
						getString(R.string.zchan_identity_android_version),
						getString(R.string.zchan_identity_fingerprint), getString(R.string.zchan_identity_timezone),
						getString(R.string.zchan_identity_language), getString(R.string.zchan_identity_app_version),
						getString(R.string.zchan_identity_source)),
				createInputTypes(ZchanChanConfiguration.IDENTITY_FIELDS_COUNT,
						InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
				new MultipleEditPreference.ListValueCodec(ZchanChanConfiguration.IDENTITY_FIELDS_COUNT));
		addPreference(identityPreference, false);
		identityPreference.setValue(configuration.obtainIdentityProfile().toValues());
		identityPreference.setOnClickListener(p -> new PreferenceDialog(p.key).show(getChildFragmentManager(),
				PreferenceDialog.class.getName()));
		identityPreference.setOnBeforeChangeListener((p, values) -> {
			boolean valid = configuration.isValidIdentityValues(values);
			if (!valid) ClickableToast.show(R.string.enter_valid_data);
			return valid;
		});
		identityPreference.setOnAfterChangeListener(p -> configuration.storeIdentityValues(p.getValue()));
		addButton(R.string.zchan_identity_generate, R.string.zchan_identity_generate__summary)
				.setOnClickListener(p -> {
					identityPreference.setValue(configuration.generateIdentityProfile().toValues());
					ClickableToast.show(R.string.completed);
				});
		addButton(R.string.zchan_identity_reset, R.string.zchan_identity_reset__summary)
				.setOnClickListener(p -> {
					identityPreference.setValue(configuration.resetIdentityProfile().toValues());
					ClickableToast.show(R.string.completed);
				});
	}

	private void addPikabuAuthorizationPreferences(PikabuChanConfiguration configuration) {
		pikabuAuthorizationPreference = addButton(getString(R.string.pikabu_sign_in), p -> {
					String userName = configuration.getAuthorizedUserName();
					return configuration.isAuthorized()
							? StringUtils.isEmpty(userName) ? getString(R.string.pikabu_authorized)
									: getString(R.string.pikabu_authorized_as__format, userName)
							: getString(R.string.pikabu_authorization__summary);
		});
		pikabuAuthorizationPreference.setEnabled(!configuration.isAuthorized());
		pikabuAuthorizationPreference.setOnClickListener(p ->
				new PikabuAuthorizationWarningDialog().show(getChildFragmentManager(),
						PikabuAuthorizationWarningDialog.class.getName()));
		pikabuLogoutPreference = addButton(R.string.pikabu_forget_session,
				R.string.pikabu_forget_session__summary);
		pikabuLogoutPreference.setEnabled(configuration.isAuthorized());
		pikabuLogoutPreference.setOnClickListener(p -> {
			PikabuAuthorizationDialog.clearPikabuCookies();
			configuration.clearAuthorization();
			configuration.commit();
			updatePikabuAuthorizationPreferences();
			ClickableToast.show(R.string.completed);
		});
	}

	private void updatePikabuAuthorizationPreferences() {
		PikabuChanConfiguration configuration =
				(PikabuChanConfiguration) Chan.get(getChanName()).configuration;
		if (pikabuAuthorizationPreference != null) {
			pikabuAuthorizationPreference.setEnabled(!configuration.isAuthorized());
			pikabuAuthorizationPreference.invalidate();
		}
		if (pikabuLogoutPreference != null) {
			pikabuLogoutPreference.setEnabled(configuration.isAuthorized());
			pikabuLogoutPreference.invalidate();
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		captchaPassPreference = null;
		userAuthorizationPreference = null;
		pikabuAuthorizationPreference = null;
		pikabuLogoutPreference = null;
		cookiePreference = null;
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

		Chan chan = Chan.get(getChanName());
		((FragmentHandler) requireActivity()).setTitleSubtitle(chan.configuration.getTitle(), null);
	}

	@Override
	public void onResume() {
		super.onResume();

		if (!ChanManager.getInstance().isExistingChanName(getChanName())) {
			((FragmentHandler) requireActivity()).removeFragment();
		} else {
			// Check every time returned from cookies fragment
			removeCookiePreferenceIfNotNeeded();
		}
	}

	@Override
	public void onChansChanged(Collection<String> changed, Collection<String> removed) {
		if (changed.contains(getChanName()) || removed.contains(getChanName())) {
			// Don't bother with updating fragment
			((FragmentHandler) requireActivity()).removeFragment();
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putBoolean(EXTRA_ANOTHER_DOMAIN_MODE, anotherDomainMode);
	}

	private void removeCookiePreferenceIfNotNeeded() {
		if (cookiePreference != null) {
			if (!ChanDatabase.getInstance().hasCookies(getChanName())) {
				removePreference(cookiePreference);
				cookiePreference = null;
			}
		}
	}

	private Preference<String> addAnotherDomainPreference(String primaryDomain) {
		Preference<String> preference = addEdit(Preferences.KEY_DOMAIN.bind(getChanName()), "",
				R.string.domain_name, primaryDomain, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		preference.setOnBeforeChangeListener((p, value) -> {
			if (primaryDomain.equals(value)) {
				p.setValue("");
				return false;
			}
			return true;
		});
		return preference;
	}

	public static class PikabuAuthorizationWarningDialog extends DialogFragment {
		public PikabuAuthorizationWarningDialog() {}

		@NonNull
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			return new AlertDialog.Builder(requireContext())
					.setTitle(R.string.pikabu_sign_in)
					.setMessage(R.string.pikabu_authorization_warning)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(R.string.pikabu_open_site, (dialog, which) -> {
						ChanFragment fragment = (ChanFragment) getParentFragment();
						if (fragment != null) {
							new PikabuAuthorizationDialog().show(fragment.getChildFragmentManager(),
									PikabuAuthorizationDialog.class.getName());
						}
					})
					.create();
		}
	}

	public static class PikabuAuthorizationDialog extends DialogFragment {
		private static final String PIKABU_URL = "https://pikabu.ru/";
		private static final String PIKABU_SETTINGS_URL = "https://pikabu.ru/settings";
		private static final String EXTRA_SIGNED_IN = "signedIn";
		private static final String EXTRA_AUTHORIZED_USER_NAME = "authorizedUserName";
		private static final String READ_SESSION_SCRIPT = "(function(){var e=document.querySelector(" +
				"'script.app__config[data-entry=\\\"initParams\\\"]');return e?e.textContent:'';})()";
		private static final String READ_SETTINGS_SAVE_STATE_SCRIPT = "(function(){" +
				"var key='__slooopPikabuSettingsState',state=window[key];" +
				"var text=function(){return document.body?document.body.innerText:'';};" +
				"var success=function(value){return value.indexOf('Настройки сохранены')>=0||" +
				"value.indexOf('Settings saved')>=0;};" +
				"if(!state){state={value:'ready',successVisible:success(text())};window[key]=state;" +
				"var changed=function(event){var target=event&&event.target;if(!target)return;" +
				"if(event.type!='click'||target.closest('[role=\\\"checkbox\\\"],[role=\\\"switch\\\"]')){" +
				"state.value='pending';state.successVisible=success(text());}};" +
				"document.addEventListener('input',changed,true);" +
				"document.addEventListener('change',changed,true);" +
				"document.addEventListener('click',changed,true);}" +
				"var value=text(),successVisible=success(value);" +
				"if(value.indexOf('Ошибка при обновлении')>=0||value.indexOf('Update failed')>=0){" +
				"state.value='error';}else if(value.indexOf('Скоро всё сохраним')>=0||" +
				"value.indexOf('Saving')>=0){state.value='pending';}" +
				"else if(state.value=='pending'&&successVisible&&!state.successVisible){" +
				"state.value='saved';}" +
				"state.successVisible=successVisible;return state.value;})()";

		private WebView webView;
		private ProgressBar progressBar;
		private TextView domainView;
		private boolean signedIn;
		private boolean sessionCheckPending;
		private boolean settingsPageActive;
		private boolean settingsStateCheckPending;
		private boolean finishingAuthorization;
		private String settingsSaveState;
		private String reportedSettingsSaveState;
		private String authorizedUserName;

		public PikabuAuthorizationDialog() {}

		@SuppressLint("SetJavaScriptEnabled")
		@NonNull
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			if (savedInstanceState != null) {
				signedIn = savedInstanceState.getBoolean(EXTRA_SIGNED_IN);
				authorizedUserName = savedInstanceState.getString(EXTRA_AUTHORIZED_USER_NAME);
			}
			android.util.DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
			float density = displayMetrics.density;
			int padding = (int) (16f * density + 0.5f);
			LinearLayout layout = new LinearLayout(requireContext());
			layout.setOrientation(LinearLayout.VERTICAL);
			layout.setMinimumHeight(Math.max((int) (360f * density + 0.5f),
					(int) (displayMetrics.heightPixels * 0.82f)));

			domainView = new TextView(requireContext());
			domainView.setText(PIKABU_URL);
			domainView.setPadding(padding, padding / 2, padding, padding / 2);
			layout.addView(domainView, new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

			progressBar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
			progressBar.setMax(100);
			layout.addView(progressBar, new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, (int) (3f * density + 0.5f)));

			webView = new WebView(requireContext());
			webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
			webView.setFocusable(true);
			webView.setFocusableInTouchMode(true);
			webView.setOnTouchListener((view, event) -> {
				if (!view.hasFocus()) view.requestFocus();
				return false;
			});
			WebViewUtils.configureCommonSettings(webView.getSettings());
			webView.getSettings().setJavaScriptEnabled(true);
			webView.getSettings().setDomStorageEnabled(true);
			webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
			webView.getSettings().setSupportMultipleWindows(false);
			webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
			CookieManager cookieManager = CookieManager.getInstance();
			cookieManager.setAcceptCookie(true);
			cookieManager.setAcceptThirdPartyCookies(webView, true);
			webView.setWebViewClient(new AuthorizationWebViewClient());
			webView.setWebChromeClient(new WebChromeClient() {
				@Override
				public void onProgressChanged(WebView view, int newProgress) {
					if (progressBar != null) progressBar.setProgress(newProgress);
				}
			});
			layout.addView(webView, new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

			if (savedInstanceState != null) webView.restoreState(savedInstanceState);
			else webView.loadUrl(PIKABU_URL);
			return new AlertDialog.Builder(requireContext())
					.setView(layout)
					.setNegativeButton(android.R.string.cancel, null)
					.setNeutralButton(R.string.pikabu_check_adult_settings, null)
					.setPositiveButton(R.string.pikabu_finish_sign_in, null)
					.create();
		}

		@Override
		public void onStart() {
			super.onStart();
			Dialog dialog = getDialog();
			Window window = dialog != null ? dialog.getWindow() : null;
			if (window != null) {
				window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
				window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
				window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
			}
			AlertDialog alertDialog = (AlertDialog) getDialog();
			if (alertDialog != null) {
				alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
						.setOnClickListener(view -> openPikabuAdultSettings());
				alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
						.setOnClickListener(view -> finishAuthorization());
				updateActionButtons();
			}
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			super.onSaveInstanceState(outState);
			if (webView != null) webView.saveState(outState);
			outState.putBoolean(EXTRA_SIGNED_IN, signedIn);
			outState.putString(EXTRA_AUTHORIZED_USER_NAME, authorizedUserName);
		}

		@Override
		public void onDestroyView() {
			if (webView != null) {
				webView.stopLoading();
				webView.setWebChromeClient(null);
				webView.setWebViewClient(null);
				webView.destroy();
				webView = null;
			}
			progressBar = null;
			domainView = null;
			sessionCheckPending = false;
			settingsPageActive = false;
			settingsStateCheckPending = false;
			super.onDestroyView();
		}

		private static boolean isDomain(String host, String domain) {
			return domain.equals(host) || host != null && host.endsWith("." + domain);
		}

		private static boolean isAllowedAuthorizationUri(Uri uri) {
			if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
			String host = uri.getHost();
			return isDomain(host, "pikabu.ru") || isDomain(host, "yandex.ru")
					|| isDomain(host, "yandex.com") || isDomain(host, "vk.com");
		}

		private static void clearPikabuCookies() {
			CookieManager cookieManager = CookieManager.getInstance();
			String cookies = cookieManager.getCookie(PIKABU_URL);
			if (!StringUtils.isEmpty(cookies)) {
				for (String part : cookies.split("; *")) {
					int equals = part.indexOf('=');
					if (equals <= 0) continue;
					String name = part.substring(0, equals).trim();
					if (name.isEmpty()) continue;
					String expired = name + "=; Max-Age=0; Path=/; Secure";
					cookieManager.setCookie(PIKABU_URL, expired);
					cookieManager.setCookie(PIKABU_URL, expired + "; Domain=.pikabu.ru");
				}
				cookieManager.flush();
			}
		}

		private void updateDomain(Uri uri) {
			if (domainView != null && uri != null && uri.getHost() != null) {
				domainView.setText("https://" + uri.getHost());
			}
		}

		private static boolean isPikabuSettingsUri(Uri uri) {
			if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
					|| !isDomain(uri.getHost(), "pikabu.ru")) return false;
			String path = uri.getPath();
			return "/settings".equals(path) || path != null && path.startsWith("/settings/");
		}

		private void updateActionButtons() {
			AlertDialog dialog = (AlertDialog) getDialog();
			if (dialog == null) return;
			View neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
			View positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
			if (neutralButton == null || positiveButton == null) return;
			neutralButton.setEnabled(signedIn);
			boolean settingsReady = !"pending".equals(settingsSaveState)
					&& !"error".equals(settingsSaveState)
					&& (!settingsPageActive || settingsSaveState != null);
			positiveButton.setEnabled(signedIn && settingsReady && !finishingAuthorization);
		}

		private void updateSettingsPage(Uri uri) {
			boolean active = isPikabuSettingsUri(uri);
			if (settingsPageActive != active) {
				settingsPageActive = active;
				if (active) {
					settingsSaveState = null;
					reportedSettingsSaveState = null;
				}
			}
			updateActionButtons();
		}

		private void checkSettingsSaveState(WebView view) {
			if (webView != view || !settingsPageActive || settingsStateCheckPending) return;
			settingsStateCheckPending = true;
			view.evaluateJavascript(READ_SETTINGS_SAVE_STATE_SCRIPT, value -> {
				settingsStateCheckPending = false;
				if (webView != view || !settingsPageActive) return;
				try {
					Object decoded = new JSONTokener(value).nextValue();
					if (decoded instanceof String) {
						String state = (String) decoded;
						if ("ready".equals(state) || "pending".equals(state)
								|| "saved".equals(state) || "error".equals(state)) {
							settingsSaveState = state;
							updateActionButtons();
							if (!state.equals(reportedSettingsSaveState)) {
								reportedSettingsSaveState = state;
								if ("saved".equals(state)) {
									CookieManager.getInstance().flush();
									ClickableToast.show(R.string.pikabu_settings_saved);
								} else if ("error".equals(state)) {
									ClickableToast.show(R.string.pikabu_settings_save_failed);
								}
							}
						}
					}
				} catch (JSONException ignored) {
					// The settings page may still be rebuilding its document.
				} finally {
					if (webView == view && settingsPageActive) {
						view.postDelayed(() -> checkSettingsSaveState(view), 350L);
					}
				}
			});
		}

		private void captureSession(WebView view, Uri uri) {
			if (signedIn || sessionCheckPending
					|| !isDomain(uri != null ? uri.getHost() : null, "pikabu.ru")) return;
			sessionCheckPending = true;
			view.evaluateJavascript(READ_SESSION_SCRIPT, value -> {
				sessionCheckPending = false;
				if (signedIn) return;
				try {
					if (StringUtils.isEmpty(value) || "null".equals(value)) return;
					Object decoded = new JSONTokener(value).nextValue();
					if (!(decoded instanceof String) || StringUtils.isEmpty((String) decoded)) return;
					JSONObject object = new JSONObject((String) decoded);
					if (object.optLong("userID", 0L) <= 0L || object.optBoolean("isDeleted", false)) return;
					if (StringUtils.isEmpty(CookieManager.getInstance().getCookie(PIKABU_URL))) return;
					signedIn = true;
					String userName = StringUtils.nullIfEmpty(object.optString("userName"));
					if (userName == null) userName = StringUtils.nullIfEmpty(object.optString("username"));
					authorizedUserName = userName;
					updateActionButtons();
					ClickableToast.show(R.string.pikabu_authorized_check_adult_settings);
				} catch (JSONException ignored) {
					// The website may still be navigating between sign-in pages.
				} finally {
					if (!signedIn && webView == view) {
						view.postDelayed(() -> {
							String url = view.getUrl();
							if (webView == view && !StringUtils.isEmpty(url)) captureSession(view, Uri.parse(url));
						}, 1000L);
					}
				}
			});
		}

		private void openPikabuAdultSettings() {
			if (!signedIn || webView == null) return;
			String tag = PikabuAdultSettingsInstructionDialog.class.getName();
			if (getChildFragmentManager().findFragmentByTag(tag) == null) {
				new PikabuAdultSettingsInstructionDialog().show(getChildFragmentManager(), tag);
			}
		}

		private void loadPikabuAdultSettings() {
			if (!signedIn || webView == null) return;
			settingsPageActive = true;
			settingsSaveState = null;
			reportedSettingsSaveState = null;
			updateActionButtons();
			webView.loadUrl(PIKABU_SETTINGS_URL);
		}

		public static class PikabuAdultSettingsInstructionDialog extends DialogFragment {
			public PikabuAdultSettingsInstructionDialog() {}

			@NonNull
			@Override
			public Dialog onCreateDialog(Bundle savedInstanceState) {
				setCancelable(false);
				AlertDialog dialog = new AlertDialog.Builder(requireContext())
						.setTitle(R.string.pikabu_check_adult_settings)
						.setMessage(R.string.pikabu_adult_settings_instruction)
						.setPositiveButton(R.string.pikabu_i_understand, (currentDialog, which) -> {
							PikabuAuthorizationDialog parent = (PikabuAuthorizationDialog) getParentFragment();
							if (parent != null) parent.loadPikabuAdultSettings();
						})
						.create();
				dialog.setCanceledOnTouchOutside(false);
				return dialog;
			}
		}

		private void finishAuthorization() {
			if (!signedIn) {
				ClickableToast.show(R.string.pikabu_sign_in_required);
				return;
			}
			if ("pending".equals(settingsSaveState) || "error".equals(settingsSaveState)
					|| settingsPageActive && settingsSaveState == null) {
				ClickableToast.show(R.string.pikabu_wait_for_settings_save);
				return;
			}
			if (finishingAuthorization || webView == null) return;
			finishingAuthorization = true;
			updateActionButtons();
			WebView currentWebView = webView;
			currentWebView.evaluateJavascript(READ_SESSION_SCRIPT,
					value -> finishAuthorization(currentWebView, value));
		}

		private void finishAuthorization(WebView currentWebView, String value) {
			if (webView != currentWebView) return;
			try {
				if (StringUtils.isEmpty(value) || "null".equals(value)) throw new JSONException("Missing session");
				Object decoded = new JSONTokener(value).nextValue();
				if (!(decoded instanceof String) || StringUtils.isEmpty((String) decoded)) {
					throw new JSONException("Missing session");
				}
				JSONObject object = new JSONObject((String) decoded);
				if (object.optLong("userID", 0L) <= 0L || object.optBoolean("isDeleted", false)) {
					ClickableToast.show(R.string.pikabu_sign_in_required);
					return;
				}
				if (!object.optBoolean("isAdultVisible", false)) {
					ClickableToast.show(R.string.pikabu_authorized_check_adult_settings);
					return;
				}
			} catch (JSONException e) {
				ClickableToast.show(R.string.pikabu_authorization_browser_failed);
				return;
			} finally {
				finishingAuthorization = false;
				updateActionButtons();
			}
			CookieManager cookieManager = CookieManager.getInstance();
			cookieManager.flush();
			String cookies = cookieManager.getCookie(PIKABU_URL);
			if (StringUtils.isEmpty(cookies)) {
				ClickableToast.show(R.string.pikabu_sign_in_required);
				return;
			}
			PikabuChanConfiguration configuration =
					(PikabuChanConfiguration) Chan.get("pikabu").configuration;
			configuration.storeAuthorization(cookies, authorizedUserName);
			configuration.commit();
			ChanFragment fragment = (ChanFragment) getParentFragment();
			dismissAllowingStateLoss();
			if (fragment != null && fragment.isAdded()) {
				new AuthorizationDialog("pikabu", AuthorizationType.USER, null)
						.show(fragment.getChildFragmentManager(), AuthorizationDialog.class.getName());
			}
		}

		private class AuthorizationWebViewClient extends WebViewClient {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				return request.isForMainFrame() && handleUri(request.getUrl());
			}

			@SuppressWarnings("deprecation")
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
				return handleUri(Uri.parse(url));
			}

			private boolean handleUri(Uri uri) {
				if (isAllowedAuthorizationUri(uri)) return false;
				ClickableToast.show(R.string.pikabu_authorization_domain_blocked);
				return true;
			}

			@Override
			public void onPageStarted(WebView view, String url, Bitmap favicon) {
				Uri uri = Uri.parse(url);
				updateDomain(uri);
				updateSettingsPage(uri);
			}

			@Override
			public void onPageFinished(WebView view, String url) {
				Uri uri = Uri.parse(url);
				updateDomain(uri);
				updateSettingsPage(uri);
				captureSession(view, uri);
				if (settingsPageActive) checkSettingsSaveState(view);
			}

			@Override
			public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
				handler.cancel();
				ClickableToast.show(R.string.invalid_certificate);
			}

			@Override
			public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
				if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
				view.destroy();
				if (webView == view) webView = null;
				ClickableToast.show(R.string.pikabu_authorization_browser_failed);
				dismissAllowingStateLoss();
				return true;
			}
		}
	}

	private enum AuthorizationType {CAPTCHA_PASS, USER}

	public static class AuthorizationDialog extends DialogFragment {
		private static final String EXTRA_CHAN_NAME = "chanName";
		private static final String EXTRA_AUTHORIZATION_TYPE = "authorizationType";
		private static final String EXTRA_AUTHORIZATION_DATA = "authorizationData";

		public AuthorizationDialog() {}

		public AuthorizationDialog(String chanName,
				AuthorizationType authorizationType, List<String> authorizationData) {
			Bundle args = new Bundle();
			args.putString(EXTRA_CHAN_NAME, chanName);
			args.putString(EXTRA_AUTHORIZATION_TYPE, authorizationType.name());
			args.putStringArrayList(EXTRA_AUTHORIZATION_DATA, authorizationData != null
					? new ArrayList<>(authorizationData) : null);
			setArguments(args);
		}

		@NonNull
		@Override
		public ProgressDialog onCreateDialog(Bundle savedInstanceState) {
			ProgressDialog dialog = new ProgressDialog(requireContext(), null);
			dialog.setMessage(getString(R.string.loading__ellipsis));
			return dialog;
		}

		@Override
		public void onActivityCreated(Bundle savedInstanceState) {
			super.onActivityCreated(savedInstanceState);

			CheckAuthorizationViewModel viewModel = new ViewModelProvider(this).get(CheckAuthorizationViewModel.class);
			if (!viewModel.hasTaskOrValue()) {
				Bundle args = requireArguments();
				Chan chan = Chan.get(args.getString(EXTRA_CHAN_NAME));
				CheckAuthorizationTask task = new CheckAuthorizationTask(viewModel, chan,
						AuthorizationType.valueOf(args.getString(EXTRA_AUTHORIZATION_TYPE)),
						args.getStringArrayList(EXTRA_AUTHORIZATION_DATA));
				task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
				viewModel.attach(task);
			}
			viewModel.observe(this, result -> {
				dismiss();
				ChanFragment chanFragment = (ChanFragment) getParentFragment();
				boolean pikabu = "pikabu".equals(requireArguments().getString(EXTRA_CHAN_NAME));
				if (chanFragment != null && pikabu) chanFragment.updatePikabuAuthorizationPreferences();
				if (result == CheckAuthorizationTask.SUCCESS) {
					ClickableToast.show(R.string.validation_completed);
				} else {
					ClickableToast.show(result);
					Preference<?> preference = null;
					if (chanFragment != null) switch (AuthorizationType.valueOf(
							requireArguments().getString(EXTRA_AUTHORIZATION_TYPE))) {
						case CAPTCHA_PASS: {
							preference = chanFragment.captchaPassPreference;
							break;
						}
						case USER: {
							if (!pikabu) preference = chanFragment.userAuthorizationPreference;
							break;
						}
					}
					if (preference != null) {
						preference.performClick();
					}
				}
			});
		}
	}

	public static class CheckAuthorizationViewModel extends TaskViewModel<CheckAuthorizationTask, ErrorItem> {}

	private static class CheckAuthorizationTask extends HttpHolderTask<Void, ErrorItem> {
		public static final ErrorItem SUCCESS = new ErrorItem("");

		private final CheckAuthorizationViewModel viewModel;
		private final Chan chan;
		private final AuthorizationType authorizationType;
		private final List<String> authorizationData;

		public CheckAuthorizationTask(CheckAuthorizationViewModel viewModel,
				Chan chan, AuthorizationType authorizationType, List<String> authorizationData) {
			super(chan);
			this.viewModel = viewModel;
			this.chan = chan;
			this.authorizationType = authorizationType;
			this.authorizationData = authorizationData;
		}

		@Override
		protected ErrorItem run(HttpHolder holder) {
			try {
				int type = -1;
				switch (authorizationType) {
					case CAPTCHA_PASS: {
						type = ChanPerformer.CheckAuthorizationData.TYPE_CAPTCHA_PASS;
						break;
					}
					case USER: {
						type = ChanPerformer.CheckAuthorizationData.TYPE_USER_AUTHORIZATION;
						break;
					}
				}
				ChanPerformer.CheckAuthorizationResult result = chan.performer.safe()
						.onCheckAuthorization(new ChanPerformer.CheckAuthorizationData(type,
								CommonUtils.toArray(authorizationData, String.class), holder));
				return result != null && result.success ? SUCCESS
						: new ErrorItem(ErrorItem.Type.INVALID_AUTHORIZATION_DATA);
			} catch (ExtensionException | HttpException | InvalidResponseException e) {
				return e.getErrorItemAndHandle();
			} finally {
				chan.configuration.commit();
			}
		}

		@Override
		protected void onComplete(ErrorItem result) {
			viewModel.handleResult(result);
		}
	}
}
