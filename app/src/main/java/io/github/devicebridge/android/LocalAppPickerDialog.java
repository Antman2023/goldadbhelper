package io.github.devicebridge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Full-screen local picker, styled from the original app's icon/name/package row hierarchy. */
final class LocalAppPickerDialog extends Dialog {
    private final Activity activity;
    private final BooleanSupplier validTarget;
    private final Consumer<String> choose;
    private final Runnable help;
    private final boolean dark;
    private final int surface, primary, secondary, line, accent, selectedSurface;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final List<LocalApps.Entry> all = new ArrayList<>(), shown = new ArrayList<>();
    private final Set<String> loadingIcons = new HashSet<>();
    private final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(2 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Adapter adapter = new Adapter();
    private Future<?> query;
    private int epoch;
    private boolean closed, loading;
    private String failure = "", selectedPackage;
    private LocalApps.Entry selectedEntry;
    private EditText search;
    private TextView count, emptyTitle, emptyDetail, selectedLabel, selectedDetail;
    private LinearLayout empty;
    private ProgressBar progress;
    private Button retry, confirm;
    private ListView list;
    private Dialog details;

    private static boolean isDark(Activity activity) {
        return (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
    LocalAppPickerDialog(Activity activity, BooleanSupplier validTarget, Consumer<String> choose, Runnable help) {
        super(activity, isDark(activity) ? android.R.style.Theme_Holo_NoActionBar : android.R.style.Theme_Holo_Light_NoActionBar);
        this.activity = activity; this.validTarget = validTarget; this.choose = choose; this.help = help;
        dark = isDark(activity); surface = dark ? 0xff171b20 : 0xffffffff;
        primary = dark ? 0xffedf1f7 : 0xff202124; secondary = dark ? 0xffaeb8c5 : 0xff68717d;
        line = dark ? 0xff303740 : 0xffe5e8ec; accent = dark ? 0xff90caf9 : 0xff1976d2;
        selectedSurface = dark ? 0xff263c50 : 0xffeaf3fc;
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column();root.setBackgroundColor(surface);root.setFocusableInTouchMode(true);
        LinearLayout toolbar = new LinearLayout(getContext());toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = action("‹", "返回应用管理");back.setTextSize(34);back.setOnClickListener(v -> cancel());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));
        TextView title = text("本机应用", 20, primary);title.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView permissions = action("权限说明", "查看本机应用权限说明");permissions.setTextSize(13);
        permissions.setPadding(dp(12), 0, dp(16), 0);permissions.setTextColor(accent);
        permissions.setOnClickListener(v -> { if (validTarget.getAsBoolean()) { dismiss();help.run(); } });
        toolbar.addView(permissions, new LinearLayout.LayoutParams(-2, dp(56)));root.addView(toolbar);

        LinearLayout searchBox = new LinearLayout(getContext());searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Ui.rounded(dark ? 0xff242b33 : 0xfff1f3f5, dp(10)));
        ImageView searchIcon = new ImageView(getContext());searchIcon.setImageResource(android.R.drawable.ic_menu_search);
        searchIcon.setColorFilter(secondary);searchIcon.setPadding(dp(12), dp(12), dp(8), dp(12));
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(dp(44), dp(48)));
        search = new EditText(getContext());search.setSingleLine(true);search.setTextSize(15);
        search.setTextColor(primary);search.setHintTextColor(secondary);search.setHint("搜索应用名称或包名");
        search.setBackgroundColor(android.graphics.Color.TRANSPARENT);search.setPadding(0, dp(8), 0, dp(8));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView clear = action("×", "清除搜索");clear.setTextSize(24);clear.setTextColor(secondary);
        clear.setOnClickListener(v -> search.setText(""));searchBox.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(48)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.setMargins(dp(16), dp(4), dp(16), dp(8));root.addView(searchBox, searchParams);
        count = text("正在读取本机应用…", 12, secondary);count.setPadding(dp(18), dp(2), dp(16), dp(10));root.addView(count);
        root.addView(divider());

        FrameLayout body = new FrameLayout(getContext());root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(getContext());list.setDivider(null);list.setDividerHeight(0);list.setAdapter(adapter);
        list.setSelector(new ColorDrawable(android.graphics.Color.TRANSPARENT));list.setCacheColorHint(surface);
        list.setOnItemClickListener((parent, view, position, id) -> select(shown.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> { showDetails(shown.get(position));return true; });
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = column();empty.setGravity(Gravity.CENTER);empty.setPadding(dp(28), dp(24), dp(28), dp(24));
        progress = new ProgressBar(getContext());empty.addView(progress, new LinearLayout.LayoutParams(dp(36), dp(36)));
        emptyTitle = text("正在读取应用", 18, primary);emptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams heading = new LinearLayout.LayoutParams(-1, -2);heading.topMargin = dp(18);empty.addView(emptyTitle, heading);
        emptyDetail = text("应用列表仅在本机处理", 14, secondary);emptyDetail.setGravity(Gravity.CENTER);
        emptyDetail.setLineSpacing(dp(3), 1);LinearLayout.LayoutParams description = new LinearLayout.LayoutParams(-1, -2);
        description.topMargin = dp(8);empty.addView(emptyDetail, description);
        retry = button("重新读取");retry.setOnClickListener(v -> load());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, dp(44));retryParams.topMargin = dp(20);
        empty.addView(retry, retryParams);body.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        root.addView(divider());LinearLayout footer = new LinearLayout(getContext());footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(16), dp(12), dp(16), dp(12));LinearLayout selectedText = column();
        selectedLabel = text("请选择一个应用", 14, primary);selectedLabel.setMaxLines(2);selectedLabel.setEllipsize(TextUtils.TruncateAt.END);
        selectedDetail = text("长按可查看完整名称与包名", 12, secondary);selectedDetail.setSingleLine(true);selectedDetail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        selectedText.addView(selectedLabel);selectedText.addView(selectedDetail);footer.addView(selectedText, new LinearLayout.LayoutParams(0, -2, 1));
        confirm = button("准备安装");confirm.setEnabled(false);styleConfirm();
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(dp(104), dp(46));confirmParams.leftMargin = dp(12);
        footer.addView(confirm, confirmParams);confirm.setOnClickListener(v -> {
            if (selectedPackage == null || !validTarget.getAsBoolean()) return;
            String pkg = selectedPackage;dismiss();choose.accept(pkg);
        });root.addView(footer);setContentView(root);
        Window window = getWindow();if(window!=null){window.setLayout(-1,-1);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);Compatibility.statusBarColor(window,dark?surface:0xff56616e);}
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){filter();}public void afterTextChanged(Editable s){}});
        root.requestFocus();load();
    }
    private void load() {
        if(closed)return;if(query!=null)query.cancel(true);final int request=++epoch;
        loading=true;failure="";all.clear();shown.clear();selectedPackage=null;selectedEntry=null;
        icons.evictAll();loadingIcons.clear();updateSelection();filter();
        query=workers.submit(()->{
            try{LocalApps.Catalog result=LocalApps.load(activity.getPackageManager());
                activity.runOnUiThread(()->{if(closed||request!=epoch)return;loading=false;failure=result.failure;all.addAll(result.entries);filter();});
            }catch(java.io.InterruptedIOException cancelled){Thread.currentThread().interrupt();}
        });
    }
    private void filter() {
        if(search==null)return;String term=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();
        shown.addAll(LocalApps.filter(all,term));
        adapter.notifyDataSetChanged();count.setText(loading?"正在读取本机应用…":failure.isEmpty()
                ?(term.isEmpty()?"共 "+all.size()+" 个应用 · 点选后准备安装":"找到 "+shown.size()+" 个应用 · 共 "+all.size()+" 个")
                :"读取失败 · 可重试或查看权限说明");
        boolean noItems=loading||shown.isEmpty();empty.setVisibility(noItems?View.VISIBLE:View.GONE);list.setVisibility(noItems?View.GONE:View.VISIBLE);
        progress.setVisibility(loading?View.VISIBLE:View.GONE);retry.setVisibility(!loading&&(all.isEmpty()||!failure.isEmpty())?View.VISIBLE:View.GONE);
        emptyTitle.setText(loading?"正在读取应用":!failure.isEmpty()?"暂时无法读取":all.isEmpty()?"未读取到本机应用":"没有匹配的应用");
        emptyDetail.setText(loading?"应用列表仅在本机处理":!failure.isEmpty()?failure:all.isEmpty()
                ?"系统返回空列表。可重新读取，或在右上角查看权限说明。":"试试应用名称或包名的其他关键词");
    }
    private void select(LocalApps.Entry entry){selectedEntry=entry;selectedPackage=entry.packageName;updateSelection();adapter.notifyDataSetChanged();}
    private void updateSelection(){
        selectedLabel.setText(selectedEntry==null?"请选择一个应用":"已选："+selectedEntry.label);
        selectedDetail.setText(selectedEntry==null?"长按可查看完整名称与包名":selectedEntry.packageName);
        confirm.setEnabled(selectedEntry!=null);styleConfirm();
    }
    private void styleConfirm(){confirm.setTextColor(confirm.isEnabled()?(dark?0xff102337:0xffffffff):secondary);confirm.setBackground(Ui.rounded(confirm.isEnabled()?accent:(dark?0xff303740:0xffe8edf2),dp(8)));}
    private void showDetails(LocalApps.Entry entry){
        TextView full=text(entry.label+"\n\n"+entry.packageName,16,primary);full.setTextIsSelectable(true);full.setPadding(dp(24),dp(16),dp(24),dp(16));
        details=new AlertDialog.Builder(getContext()).setTitle("应用信息").setView(full).setNegativeButton("关闭",null)
                .setPositiveButton("选择此应用",(d,w)->select(entry)).show();
    }
    @Override protected void onStop(){
        closed=true;epoch++;if(query!=null)query.cancel(true);workers.shutdownNow();icons.evictAll();loadingIcons.clear();
        if(details!=null)details.dismiss();super.onStop();
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size,int color){TextView view=new TextView(getContext());view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private TextView action(String value,String description){TextView view=text(value,18,primary);view.setGravity(Gravity.CENTER);view.setContentDescription(description);view.setBackground(Compatibility.ripple(dark?0x446b8fb5:0x221976d2,null,null));return view;}
    private Button button(String value){Button view=new Button(getContext());view.setText(value);view.setTextSize(14);view.setAllCaps(false);view.setTextColor(accent);return view;}
    private View divider(){View view=new View(getContext());view.setBackgroundColor(line);view.setLayoutParams(new LinearLayout.LayoutParams(-1,Math.max(1,dp(.5f))));return view;}
    private int dp(float value){return Math.round(activity.getResources().getDisplayMetrics().density*value);}

    private final class Adapter extends BaseAdapter {
        public int getCount(){return shown.size();}public Object getItem(int position){return shown.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View old,ViewGroup parent){
            Row row=old instanceof Row?(Row)old:new Row();LocalApps.Entry entry=shown.get(position);
            boolean selected=entry.packageName.equals(selectedPackage);row.name.setText(entry.label);row.packageName.setText(entry.packageName);
            row.setBackground(Compatibility.ripple(dark?0x446b8fb5:0x331976d2,new ColorDrawable(selected?selectedSurface:surface),null));
            row.indicator.selected=selected;row.indicator.invalidate();row.icon.setImageBitmap(icons.get(entry.packageName));
            if(icons.get(entry.packageName)==null){row.icon.setImageResource(android.R.drawable.sym_def_app_icon);loadIcon(entry.packageName);}
            row.setContentDescription(entry.label+"，"+entry.packageName+(selected?"，已选择":"，未选择")+"。长按查看完整信息");row.setSelected(selected);return row;
        }
    }
    private final class Row extends LinearLayout {
        final TextView name,packageName;final ImageView icon;final Indicator indicator;
        Row(){super(LocalAppPickerDialog.this.getContext());setOrientation(VERTICAL);setLayoutParams(new AbsListView.LayoutParams(-1,-2));setMinimumHeight(dp(74));
            LinearLayout content=new LinearLayout(getContext());content.setGravity(Gravity.CENTER_VERTICAL);content.setPadding(dp(16),dp(13),dp(16),dp(13));
            icon=new ImageView(getContext());icon.setScaleType(ImageView.ScaleType.FIT_CENTER);content.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
            LinearLayout labels=column();name=text("",18,primary);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setLineSpacing(0,1.05f);
            packageName=text("",12,secondary);packageName.setSingleLine(true);packageName.setEllipsize(TextUtils.TruncateAt.MIDDLE);packageName.setPadding(0,dp(3),0,0);
            labels.addView(name);labels.addView(packageName);LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.leftMargin=dp(16);labelParams.rightMargin=dp(12);content.addView(labels,labelParams);
            indicator=new Indicator();content.addView(indicator,new LinearLayout.LayoutParams(dp(22),dp(22)));addView(content,new LinearLayout.LayoutParams(-1,-2));
            View rule=divider();LinearLayout.LayoutParams ruleParams=new LinearLayout.LayoutParams(-1,Math.max(1,dp(.5f)));ruleParams.leftMargin=dp(74);ruleParams.rightMargin=dp(16);addView(rule,ruleParams);
        }
    }
    private final class Indicator extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);boolean selected;
        Indicator(){super(LocalAppPickerDialog.this.getContext());}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float x=getWidth()/2f,y=getHeight()/2f,r=dp(9);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.5f));paint.setColor(selected?accent:secondary);canvas.drawCircle(x,y,r,paint);if(selected){paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,dp(5),paint);}}
    }
    private void loadIcon(String packageName){
        if(closed||!loadingIcons.add(packageName))return;final int request=epoch;
        workers.submit(()->{
            Bitmap result=null;
            try{if(Thread.currentThread().isInterrupted())return;Drawable drawable=activity.getPackageManager().getApplicationIcon(packageName);
                int size=Math.max(42,Math.min(128,dp(42)));result=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
                drawable.setBounds(0,0,size,size);drawable.draw(new Canvas(result));
            }catch(android.content.pm.PackageManager.NameNotFoundException|RuntimeException unavailable){}
            catch(OutOfMemoryError lowMemory){}
            final Bitmap bitmap=result;activity.runOnUiThread(()->{if(closed||request!=epoch)return;if(bitmap!=null){icons.put(packageName,bitmap);loadingIcons.remove(packageName);}adapter.notifyDataSetChanged();});
        });
    }
}
