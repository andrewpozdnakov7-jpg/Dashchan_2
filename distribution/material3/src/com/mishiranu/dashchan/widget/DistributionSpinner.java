package com.mishiranu.dashchan.widget;

import android.content.Context;
import androidx.appcompat.widget.AppCompatSpinner;

/** Uses the same widget family as the GitHub Material dependency without changing popup ownership. */
abstract class DistributionSpinner extends AppCompatSpinner {
	DistributionSpinner(Context context) {
		super(context, null, android.R.attr.spinnerStyle, android.widget.Spinner.MODE_DIALOG);
	}
}
