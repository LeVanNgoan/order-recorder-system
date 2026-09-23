package vn.orderrecorder.shopee;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class TodayOrdersActivity extends Activity {
    private static final int ORANGE=Color.rgb(238,77,45), GREEN=Color.rgb(18,155,73), TEXT=Color.rgb(32,38,45), MUTED=Color.rgb(105,113,122), BG=Color.rgb(246,247,249);
    private TextView summary, rows, empty;
    private EditText search;
    private List<OrderRecord> today=new ArrayList<>();

    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(buildUi());render();}
    @Override protected void onResume(){super.onResume();render();}

    private View buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(18),dp(12),dp(18),dp(12));header.setBackgroundColor(ORANGE);
        header.addView(text("Đơn hàng hôm nay",24,true,Color.WHITE));
        summary=text("",13,true,Color.WHITE);header.addView(summary);root.addView(header,new LinearLayout.LayoutParams(-1,dp(84)));

        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(14),dp(14),dp(14),dp(12));
        search=new EditText(this);search.setHint("Tìm theo SPF-xxxx hoặc SĐT");search.setSingleLine(true);search.setTextSize(14);search.setBackground(round(Color.WHITE,12,Color.rgb(222,226,230)));search.setPadding(dp(14),0,dp(14),0);content.addView(search,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView schema=text("STT   MÃ ĐƠN HÀNG   ·   SĐT   ·   THỜI GIAN NHẬN ĐƠN",11,true,MUTED);schema.setPadding(dp(3),dp(12),0,dp(8));content.addView(schema);

        ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(13),dp(12),dp(13),dp(12));box.setBackground(round(Color.WHITE,14,Color.rgb(227,230,233)));
        rows=text("",13,false,TEXT);rows.setTypeface(Typeface.MONOSPACE);rows.setLineSpacing(dp(3),1f);box.addView(rows);
        empty=text("Không có đơn phù hợp.",14,false,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(26),0,dp(26));box.addView(empty);scroll.addView(box);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView footer=text("Developer by Ngoan, Le Van",12,true,MUTED);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(14),0,dp(4));content.addView(footer);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){renderRows(s==null?"":s.toString());}public void afterTextChanged(Editable e){}});
        return root;
    }

    private void render(){
        today=OrderStore.getToday(this);int done=0,review=0;for(OrderRecord r:today){if(r.isCompleted())done++;else if("needs_review".equals(r.status))review++;}
        summary.setText("Tổng "+today.size()+" · Đã có SĐT "+done+(review>0?" · Cần kiểm tra "+review:""));
        renderRows(search==null?"":search.getText().toString());
    }

    private void renderRows(String query){
        String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);String qDigits=q.replaceAll("\\D","");StringBuilder out=new StringBuilder();SimpleDateFormat f=new SimpleDateFormat("HH:mm:ss dd-MM-yyyy",Locale.getDefault());f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));int stt=0;
        for(OrderRecord r:today){
            String code=r.businessOrderCode();String phone=TextParser.normalizePhone(r.receiverPhone);String hay=(code+" "+phone+" "+r.displayOrderId+" "+r.fullOrderId).toLowerCase(Locale.ROOT);
            if(!q.isEmpty()&&!hay.contains(q)&&!(qDigits.length()>=3&&phone.contains(qDigits)))continue;
            stt++;if(out.length()>0)out.append("\n────────────────────────\n");
            out.append(stt).append("   ").append(code.isEmpty()?"Chưa xác định":code).append("\n");
            out.append("    ").append(phone.isEmpty()?"Chưa có SĐT":phone).append("\n");
            out.append("    ").append(r.receivedAt>0?f.format(new Date(r.receivedAt)):"—");
            if("needs_review".equals(r.status) && !r.isCompleted()) out.append("\n    ⚠ CẦN KIỂM TRA · chưa ghi nhận được SĐT");
        }
        rows.setText(out.toString());empty.setVisibility(stt==0?View.VISIBLE:View.GONE);rows.setVisibility(stt==0?View.GONE:View.VISIBLE);
    }

    private TextView text(String s,int z,boolean bold,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private GradientDrawable round(int fill,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
