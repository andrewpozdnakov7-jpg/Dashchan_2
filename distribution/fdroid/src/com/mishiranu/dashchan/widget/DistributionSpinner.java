package com.mishiranu.dashchan.widget;

import android.content.Context;
import android.widget.Spinner;

/** Framework widget for the distribution without the GitHub Material/AppCompat dependencies. */
abstract class DistributionSpinner extends Spinner {
	DistributionSpinner(Context context) {
		super(context, null, android.R.attr.spinnerStyle, MODE_DIALOG);
	}
}
