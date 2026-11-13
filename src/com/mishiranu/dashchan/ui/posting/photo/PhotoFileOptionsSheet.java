package com.mishiranu.dashchan.ui.posting.photo;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.security.AppSecurity;
import com.mishiranu.dashchan.util.InterfaceMotion;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/** One modal surface and one scroll viewport. The caller owns the committed editor snapshot. */
public final class PhotoFileOptionsSheet extends BottomSheetDialog {
    private final Activity owner;
    private final int width, height;
    private final boolean pixelsChanged, metadataSupported, spoilerSupported;
    private final Consumer<PhotoAttachmentOptions> commit;
    private final Consumer<String> changed;
    private final Runnable closed;
    private final LinearLayout header, body;
    private final Footer footer;
    private final TextView title;
    private final MaterialButton cancel, done;
    private PhotoAttachmentOptions.Builder draft;
    private MaterialSwitch metadata, reencode;
    private TextView metadataHint, reencodeHint;
    private View controls, qualityRow;
    private boolean binding, sizePage, detaching;
    private int pendingWidth, pendingHeight;
    private Object nestedBack;

    public PhotoFileOptionsSheet(Activity owner, PhotoAttachmentOptions options, String restored,
            boolean pixelsChanged, int width, int height, boolean metadataSupported, boolean spoilerSupported,
            Consumer<PhotoAttachmentOptions> commit, Consumer<String> changed, Runnable closed) {
        super(new ContextThemeWrapper(owner,R.style.Theme_Slooop_PhotoFileOptions));
        this.owner=owner; this.width=width; this.height=height; this.pixelsChanged=pixelsChanged;
        this.metadataSupported=metadataSupported; this.spoilerSupported=spoilerSupported;
        this.commit=commit; this.changed=changed; this.closed=closed;
        draft=new PhotoAttachmentOptions.Builder(options);
        if (restored!=null) try {
            JSONObject j=new JSONObject(restored);
            draft=new PhotoAttachmentOptions.Builder(PhotoAttachmentOptions.fromJson(j.getJSONObject("draft")));
            sizePage=j.optBoolean("sizePage"); pendingWidth=j.optInt("pendingWidth"); pendingHeight=j.optInt("pendingHeight");
            PhotoAttachmentOptions.Builder check=new PhotoAttachmentOptions.Builder(draft.build());
            check.width=pendingWidth; check.height=pendingHeight; check.build();
        } catch (JSONException | IllegalArgumentException e) { draft=new PhotoAttachmentOptions.Builder(options); sizePage=false; }
        AppSecurity.protectDialog(this);
        Context c=getContext();
        header=new LinearLayout(c); header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dimen(R.dimen.pe_file_sheet_padding_horizontal),dimen(R.dimen.pe_file_sheet_handle_top),dimen(R.dimen.pe_file_sheet_padding_horizontal),dp(12));
        View handle=new View(c); GradientDrawable handleShape=new GradientDrawable();
        handleShape.setColor(EditorPalette.MUTED); handleShape.setCornerRadius(dp(2)); handle.setBackground(handleShape);
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dimen(R.dimen.pe_file_sheet_handle_width),dimen(R.dimen.pe_file_sheet_handle_height));
        hp.gravity=Gravity.CENTER_HORIZONTAL; hp.bottomMargin=dimen(R.dimen.pe_file_sheet_title_gap); header.addView(handle,hp);
        title=text(22,EditorPalette.TEXT); title.setTypeface(com.mishiranu.dashchan.util.ResourceUtils.TYPEFACE_MEDIUM); title.setAccessibilityHeading(true); header.addView(title);
        body=new LinearLayout(c); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dimen(R.dimen.pe_file_sheet_padding_horizontal),0,dimen(R.dimen.pe_file_sheet_padding_horizontal),0);
        NestedScrollView scroll=new NestedScrollView(c); scroll.setFillViewport(false); scroll.addView(body);
        footer=new Footer(c); footer.setPadding(dimen(R.dimen.pe_file_sheet_padding_horizontal),dimen(R.dimen.pe_file_footer_padding_vertical),
                dimen(R.dimen.pe_file_sheet_padding_horizontal),dimen(R.dimen.pe_file_footer_padding_vertical));
        cancel=button(android.R.string.cancel,false); done=button(R.string.pe_color_done,true);
        footer.addView(cancel,new LinearLayout.LayoutParams(0,-2,1)); footer.addView(done,new LinearLayout.LayoutParams(0,-2,1));
        cancel.setOnClickListener(v->{if(sizePage) leaveSize(false); else cancel();});
        done.setOnClickListener(v->{
            if(sizePage) {leaveSize(true);return;}
            PhotoAttachmentOptions result=draft.build();
            done.setEnabled(false); commit.accept(result); dismiss();
        });
        ViewGroup content=new ViewGroup(c) {
            { addView(header); addView(scroll); addView(footer); }
            @Override protected void onMeasure(int ws,int hs) {
                int w=MeasureSpec.getSize(ws), cap=owner.getWindow().getDecorView().getHeight();
                if(cap<=0) cap=getResources().getDisplayMetrics().heightPixels;
                WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(this);
                if(insets!=null) cap-=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()).top;
                if(MeasureSpec.getMode(hs)!=MeasureSpec.UNSPECIFIED) cap=Math.min(cap,MeasureSpec.getSize(hs));
                int exact=MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY), free=MeasureSpec.makeMeasureSpec(Math.max(0,cap),MeasureSpec.AT_MOST);
                header.measure(exact,free); footer.measure(exact,free);
                scroll.measure(exact,MeasureSpec.makeMeasureSpec(Math.max(0,cap-header.getMeasuredHeight()-footer.getMeasuredHeight()),MeasureSpec.AT_MOST));
                setMeasuredDimension(w,header.getMeasuredHeight()+scroll.getMeasuredHeight()+footer.getMeasuredHeight());
            }
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
                int y=0; for(View child:new View[]{header,scroll,footer}) { child.layout(0,y,r-l,y+child.getMeasuredHeight()); y+=child.getMeasuredHeight(); }
            }
        };
        setContentView(content);
        ViewCompat.setOnApplyWindowInsetsListener(content,(v,insets)->{
            int bottom=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()).bottom;
            int pad=dimen(R.dimen.pe_file_footer_padding_vertical);
            if(footer.getPaddingBottom()!=pad+bottom) footer.setPadding(footer.getPaddingLeft(),pad,footer.getPaddingRight(),pad+bottom);
            return insets;
        });
        setDismissWithAnimation(InterfaceMotion.isEnabled() && InterfaceMotion.duration(180)>0);
        getBehavior().setFitToContents(true); getBehavior().setSkipCollapsed(true); getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        setOnKeyListener((d,key,event)->{
            if(key==KeyEvent.KEYCODE_BACK && sizePage) {
                if(com.mishiranu.dashchan.widget.BackKeyPolicy.isConfirmedRelease(event)) leaveSize(false);
                return true;
            }
            return false;
        });
        setOnShowListener(d->{
            View sheet=findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if(sheet!=null) {
                GradientDrawable background=new GradientDrawable(); background.setColor(EditorPalette.SECONDARY);
                float radius=dimen(R.dimen.pe_file_sheet_corner);
                background.setCornerRadii(new float[]{radius,radius,radius,radius,0,0,0,0}); sheet.setBackground(background);
                sheet.getLayoutParams().height=ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            if(getWindow()!=null) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); getWindow().setDimAmount(.24f);
                if(!InterfaceMotion.isEnabled() || InterfaceMotion.duration(180)==0) getWindow().setWindowAnimations(0);
            }
            ViewCompat.requestApplyInsets(content); updateNestedBack();
        });
        setOnDismissListener(d->{
            unregisterNestedBack(); TransitionManager.endTransitions(body);
            if(!detaching) closed.run();
        });
        rebuild();
    }
    private int dp(float value) { return Math.round(value*getContext().getResources().getDisplayMetrics().density); }
    private int dimen(int id) { return getContext().getResources().getDimensionPixelSize(id); }
    private TextView text(int sp,int color) {
        TextView v=new TextView(getContext());
        int resource=sp==22?R.dimen.pe_file_sheet_title_size:sp==12?R.dimen.pe_file_option_supporting_size:R.dimen.pe_file_option_label_size;
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,getContext().getResources().getDimension(resource));
        v.setTextColor(color);return v;
    }
    private MaterialButton button(int label,boolean filled) {
        MaterialButton b=new MaterialButton(getContext(),null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setCornerRadius(dp(24));
        b.setInsetTop(0); b.setInsetBottom(0); b.setStrokeWidth(0);
        b.setBackgroundTintList(ColorStateList.valueOf(filled?EditorPalette.ACCENT:Color.TRANSPARENT));
        b.setTextColor(filled?EditorPalette.ON_ACCENT:EditorPalette.ACCENT); return b;
    }
    private void rebuild() {
        TransitionManager.endTransitions(body); body.removeAllViews();
        title.setText(sizePage?R.string.pe_file_size:R.string.pe_file_options);
        if(sizePage) {buildSizes();return;}
        row(R.string.pe_file_unique_hash,draft.uniqueHash,true,null,value->draft.uniqueHash=value);
        metadataHint=text(12,EditorPalette.MUTED);
        metadata=row(R.string.pe_file_remove_metadata,false,true,metadataHint,value->draft.removeMetadata=value);
        row(R.string.remove_file_name,draft.removeFileName,true,null,value->draft.removeFileName=value);
        reencodeHint=text(12,EditorPalette.MUTED);
        reencode=row(R.string.reencode_image,false,true,reencodeHint,value->draft.reencode=value);
        LinearLayout output=new LinearLayout(getContext()); output.setOrientation(LinearLayout.VERTICAL);
        output.setPadding(0,dimen(R.dimen.pe_file_output_controls_top),0,0); body.addView(output); controls=output;
        LinearLayout format=new LinearLayout(getContext()); format.setGravity(Gravity.CENTER_VERTICAL); output.addView(format);
        if(getContext().getResources().getConfiguration().fontScale>1.5f) format.setOrientation(LinearLayout.VERTICAL);
        TextView formatLabel=text(16,EditorPalette.TEXT); formatLabel.setText(R.string.pe_file_format); format.addView(formatLabel);
        MaterialButtonToggleGroup group=new MaterialButtonToggleGroup(getContext()); group.setSingleSelection(true); group.setSelectionRequired(true);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(format.getOrientation()==LinearLayout.VERTICAL?-1:0,-2,
                format.getOrientation()==LinearLayout.VERTICAL?0:1); gp.setMarginStart(dp(8)); format.addView(group,gp);
        int jpegId=View.generateViewId(), pngId=View.generateViewId();
        for(int id:new int[]{jpegId,pngId}) {
            MaterialButton b=new MaterialButton(getContext(),null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setId(id); b.setText(id==jpegId?"JPEG":"PNG"); b.setAllCaps(false); b.setCheckable(true); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
            b.setInsetTop(0);b.setInsetBottom(0);b.setCornerRadius(dp(20));b.setStrokeColor(ColorStateList.valueOf(EditorPalette.BORDER));
            b.setBackgroundTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{EditorPalette.ACCENT,Color.TRANSPARENT}));
            b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{EditorPalette.ON_ACCENT,EditorPalette.TEXT}));
            group.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        }
        group.check(draft.png?pngId:jpegId);
        group.addOnButtonCheckedListener((g,id,checked)->{if(checked&&!binding){draft.png=id==pngId;sync(true);notifyState();}});
        LinearLayout quality=new LinearLayout(getContext()); quality.setOrientation(LinearLayout.VERTICAL); output.addView(quality); qualityRow=quality;
        LinearLayout qualityHeading=new LinearLayout(getContext()); qualityHeading.setGravity(Gravity.CENTER_VERTICAL); quality.addView(qualityHeading);
        TextView qTitle=text(16,EditorPalette.TEXT); qTitle.setText(R.string.pe_file_quality_jpeg);qualityHeading.addView(qTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView qValue=text(16,EditorPalette.TEXT); qualityHeading.addView(qValue); qValue.setText(percent(draft.quality));
        Slider slider=new Slider(getContext()); slider.setValueFrom(1); slider.setValueTo(100); slider.setStepSize(1); slider.setValue(draft.quality);
        slider.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE); slider.setContentDescription(getContext().getString(R.string.pe_file_quality_jpeg));
        slider.setTrackActiveTintList(ColorStateList.valueOf(EditorPalette.ACCENT));slider.setThumbTintList(ColorStateList.valueOf(EditorPalette.ACCENT));
        slider.setTrackInactiveTintList(ColorStateList.valueOf(EditorPalette.BORDER)); quality.addView(slider,new LinearLayout.LayoutParams(-1,dp(48)));
        slider.addOnChangeListener((view,value,fromUser)->{if(fromUser){draft.quality=Math.round(value);qValue.setText(percent(draft.quality));notifyState();}});
        LinearLayout sizeRow=new LinearLayout(getContext());sizeRow.setGravity(Gravity.CENTER_VERTICAL);output.addView(sizeRow);
        boolean stacked=getContext().getResources().getConfiguration().fontScale>1.5f;
        if(stacked)sizeRow.setOrientation(LinearLayout.VERTICAL);
        TextView sizeTitle=text(16,EditorPalette.TEXT);sizeTitle.setText(R.string.pe_file_size);
        sizeTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sizeRow.addView(sizeTitle,new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1));
        MaterialButton size=button(R.string.pe_file_size,false);size.setText(sizeValue(draft.width,draft.height));
        size.setSingleLine(false);size.setMaxLines(Integer.MAX_VALUE);
        size.setContentDescription(getContext().getString(R.string.pe_file_size)+", "+sizeLabel(draft.width,draft.height));
        size.setIconResource(R.drawable.ic_arrow_drop_down);size.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
        size.setIconSize(dp(24));size.setIconTint(ColorStateList.valueOf(EditorPalette.TEXT));
        size.setTextColor(EditorPalette.TEXT);sizeRow.addView(size,new LinearLayout.LayoutParams(stacked?-1:-2,-2));
        size.setOnClickListener(v->{pendingWidth=draft.width;pendingHeight=draft.height;sizePage=true;rebuild();updateNestedBack();notifyState();});
        if(spoilerSupported) row(R.string.spoiler,draft.spoiler,true,null,value->draft.spoiler=value);
        sync(false);
    }
    private String percent(int quality) { java.text.NumberFormat f=java.text.NumberFormat.getPercentInstance(); f.setMaximumFractionDigits(0); return f.format(quality/100d); }
    private MaterialSwitch row(int label,boolean value,boolean enabled,TextView supporting,Consumer<Boolean> change) {
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dimen(supporting==null?R.dimen.pe_file_option_min_height:R.dimen.pe_file_option_supporting_min_height));
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); body.addView(row,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout labels=new LinearLayout(getContext()); labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(0,dp(6),dp(8),dp(6));
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name=text(16,EditorPalette.TEXT);name.setText(label);labels.addView(name);
        if(supporting!=null) labels.addView(supporting);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        MaterialSwitch toggle=new MaterialSwitch(getContext()); toggle.setMinHeight(dp(48)); toggle.setMinimumHeight(dp(48));
        toggle.setContentDescription(getContext().getString(label));toggle.setChecked(value);toggle.setEnabled(enabled);row.addView(toggle,new LinearLayout.LayoutParams(-2,-2));
        toggle.setOnCheckedChangeListener((v,on)->{if(!binding){change.accept(on);sync(true);notifyState();}});
        row.setOnClickListener(v->{if(toggle.isEnabled())toggle.toggle();}); return toggle;
    }
    private void sync(boolean animate) {
        if(sizePage || metadata==null || reencode==null) return;
        if(animate && InterfaceMotion.isEnabled() && InterfaceMotion.duration(180)>0) {
            AutoTransition transition=new AutoTransition();transition.setDuration(InterfaceMotion.duration(180));transition.setInterpolator(InterfaceMotion.STANDARD);
            TransitionManager.beginDelayedTransition(body,transition);
        }
        binding=true;
        boolean effective=pixelsChanged||draft.reencode;
        metadata.setChecked(effective||draft.removeMetadata);metadata.setEnabled(metadataSupported&&!effective);
        metadataHint.setText(effective?R.string.pe_file_metadata_automatic:R.string.pe_file_option_unsupported);
        metadataHint.setVisibility(effective||!metadataSupported?View.VISIBLE:View.GONE);
        ((View)metadataHint.getParent().getParent()).setMinimumHeight(dimen(metadataHint.getVisibility()==View.VISIBLE?R.dimen.pe_file_option_supporting_min_height:R.dimen.pe_file_option_min_height));
        metadata.setContentDescription(getContext().getString(R.string.pe_file_remove_metadata)+(metadataHint.getVisibility()==View.VISIBLE?", "+metadataHint.getText():""));
        reencode.setChecked(effective);reencode.setEnabled(!pixelsChanged);reencodeHint.setText(R.string.pe_file_reencoding_required);
        reencodeHint.setVisibility(pixelsChanged?View.VISIBLE:View.GONE);
        ((View)reencodeHint.getParent().getParent()).setMinimumHeight(dimen(pixelsChanged?R.dimen.pe_file_option_supporting_min_height:R.dimen.pe_file_option_min_height));
        reencode.setContentDescription(getContext().getString(R.string.reencode_image)+(pixelsChanged?", "+reencodeHint.getText():""));
        controls.setVisibility(effective?View.VISIBLE:View.GONE);qualityRow.setVisibility(draft.png?View.GONE:View.VISIBLE);
        binding=false;
    }
    private String sizeLabel(int w,int h) {
        return (w==0?getContext().getString(R.string.pe_export_no_resize)+" · ":"")+sizeValue(w,h);
    }
    private String sizeValue(int w,int h) {
        int[] size=PhotoAttachmentOptions.dimensions(width,height,w,h);
        return String.format(Locale.getDefault(),"%d × %d",size[0],size[1]);
    }
    private void buildSizes() {
        ArrayList<int[]> sizes=new ArrayList<>();sizes.add(new int[]{0,0});
        if(draft.width>0) sizes.add(new int[]{draft.width,draft.height});
        for(int side:new int[]{4096,3072,2048,1024}) if(side<Math.max(width,height)) {
            int[] size=EditorExportSizing.dimensions(width,height,side);boolean duplicate=false;
            for(int[] prior:sizes) if(prior[0]==size[0]&&prior[1]==size[1]) duplicate=true;
            if(!duplicate)sizes.add(size);
        }
        for(int[] size:sizes) {
            MaterialRadioButton radio=new MaterialRadioButton(getContext());radio.setText(sizeLabel(size[0],size[1]));
            radio.setTextColor(EditorPalette.TEXT);radio.setTextSize(16);radio.setMinHeight(dp(48));radio.setButtonTintList(ColorStateList.valueOf(EditorPalette.ACCENT));
            radio.setChecked(size[0]==pendingWidth&&size[1]==pendingHeight);body.addView(radio,new LinearLayout.LayoutParams(-1,-2));
            radio.setOnClickListener(v->{pendingWidth=size[0];pendingHeight=size[1];
                for(int i=0;i<body.getChildCount();i++) ((MaterialRadioButton)body.getChildAt(i)).setChecked(body.getChildAt(i)==radio);
                notifyState();});
        }
    }
    private void leaveSize(boolean apply) {
        if(apply){draft.width=pendingWidth;draft.height=pendingHeight;}
        sizePage=false;unregisterNestedBack();rebuild();notifyState();
    }
    private void updateNestedBack() {
        unregisterNestedBack();
        if(Build.VERSION.SDK_INT>=33 && sizePage && isShowing()) {
            android.window.OnBackInvokedCallback callback=()->leaveSize(false); nestedBack=callback;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
        }
    }
    private void unregisterNestedBack() {
        if(Build.VERSION.SDK_INT>=33 && nestedBack!=null) {getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)nestedBack);nestedBack=null;}
    }
    public String snapshot() {
        try {return new JSONObject().put("draft",draft.build().toJson()).put("sizePage",sizePage)
                .put("pendingWidth",pendingWidth).put("pendingHeight",pendingHeight).toString();}
        catch(JSONException e){throw new IllegalStateException(e);}
    }
    private void notifyState() {changed.accept(snapshot());}
    public void detach() {detaching=true;unregisterNestedBack();TransitionManager.endTransitions(body);setDismissWithAnimation(false);dismiss();}
    private static final class Footer extends LinearLayout {
        Footer(Context c){super(c);setGravity(Gravity.CENTER_VERTICAL);}
        @Override protected void onMeasure(int ws,int hs){
            int total=getPaddingLeft()+getPaddingRight();
            for(int i=0;i<getChildCount();i++){View v=getChildAt(i);v.measure(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),hs);total+=v.getMeasuredWidth();}
            boolean stack=MeasureSpec.getMode(ws)!=MeasureSpec.UNSPECIFIED&&total>MeasureSpec.getSize(ws);
            setOrientation(stack?VERTICAL:HORIZONTAL);
            for(int i=0;i<getChildCount();i++){LayoutParams p=(LayoutParams)getChildAt(i).getLayoutParams();p.width=stack?-1:0;p.weight=stack?0:1;}
            super.onMeasure(ws,hs);
        }
    }
}
