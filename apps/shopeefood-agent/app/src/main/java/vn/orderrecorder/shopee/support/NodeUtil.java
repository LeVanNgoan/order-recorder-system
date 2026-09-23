package vn.orderrecorder.shopee;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

public final class NodeUtil {
    private NodeUtil() {}

    public static String text(AccessibilityNodeInfo root) {
        if(root==null)return"";
        StringBuilder out=new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int n=0;
        while(!q.isEmpty()&&n++<2600){
            AccessibilityNodeInfo x=q.removeFirst();
            append(out,x.getText());append(out,x.getContentDescription());
            if(Build.VERSION.SDK_INT>=26)append(out,x.getHintText());
            String id=x.getViewIdResourceName();if(id!=null&&!id.isEmpty())out.append(id).append('\n');
            for(int i=0;i<x.getChildCount();i++){AccessibilityNodeInfo c=x.getChild(i);if(c!=null)q.addLast(c);}
        }
        return out.toString();
    }

    private static void append(StringBuilder b,CharSequence c){
        if(c!=null){String s=c.toString().trim();if(!s.isEmpty())b.append(s).append('\n');}
    }

    public static Rect findLabelBounds(AccessibilityNodeInfo root,String label){
        AccessibilityNodeInfo n=findLabel(root,label);if(n==null)return null;
        Rect r=bestBounds(n);return r.isEmpty()?null:r;
    }

    public static AccessibilityNodeInfo findLabel(AccessibilityNodeInfo root,String label){
        if(root==null)return null;
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int n=0;
        AccessibilityNodeInfo contains=null;
        while(!q.isEmpty()&&n++<2600){
            AccessibilityNodeInfo x=q.removeFirst();
            String t=x.getText()==null?"":x.getText().toString().trim();
            if(t.equalsIgnoreCase(label))return x;
            if(contains==null&&!t.isEmpty()&&t.toLowerCase().contains(label.toLowerCase()))contains=x;
            for(int i=0;i<x.getChildCount();i++){AccessibilityNodeInfo c=x.getChild(i);if(c!=null)q.addLast(c);}
        }
        return contains;
    }

    public static boolean clickPhoneOnRow(AccessibilityNodeInfo root,String label){
        AccessibilityNodeInfo labelNode=findLabel(root,label);if(labelNode==null)return false;
        Rect lr=bestBounds(labelNode);if(lr.isEmpty())return false;
        AccessibilityNodeInfo best=null;int bestScore=Integer.MAX_VALUE;
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int n=0;
        while(!q.isEmpty()&&n++<2600){
            AccessibilityNodeInfo x=q.removeFirst();Rect r=bestBounds(x);
            if(!r.isEmpty()&&isActionable(x)){
                int dy=Math.abs(r.centerY()-lr.centerY());int dx=r.centerX()-lr.centerX();
                CharSequence d=x.getContentDescription();String ds=d==null?"":d.toString().toLowerCase();
                String rid=x.getViewIdResourceName()==null?"":x.getViewIdResourceName().toLowerCase();
                boolean phoneHint=ds.contains("phone")||ds.contains("call")||ds.contains("điện thoại")||ds.contains("gọi")||rid.contains("phone")||rid.contains("call");
                if(dy<=Math.max(110,lr.height()*3)&&(dx>0||phoneHint)){
                    int score=dy*6+(dx>0?Math.max(0,160-dx):900)-(phoneHint?1400:0);
                    if(score<bestScore){bestScore=score;best=x;}
                }
            }
            for(int i=0;i<x.getChildCount();i++){AccessibilityNodeInfo c=x.getChild(i);if(c!=null)q.addLast(c);}
        }
        return performClick(best);
    }

    public static String singlePhoneOnRow(AccessibilityNodeInfo root,String targetLabel,String otherLabel){
        if(root==null)return"";
        AccessibilityNodeInfo target=findLabel(root,targetLabel);if(target==null)return"";
        Rect tr=bestBounds(target);if(tr.isEmpty())return"";
        Rect or=null;AccessibilityNodeInfo other=findLabel(root,otherLabel);
        if(other!=null){Rect tmp=bestBounds(other);if(!tmp.isEmpty())or=tmp;}
        int spacing=or==null?180:Math.max(80,Math.abs(tr.centerY()-or.centerY()));
        int allowed=Math.max(70,(int)(spacing*0.46f));Set<String> rowPhones=new LinkedHashSet<>();
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int n=0;
        while(!q.isEmpty()&&n++<2600){
            AccessibilityNodeInfo x=q.removeFirst();StringBuilder local=new StringBuilder();
            if(x.getText()!=null)local.append(x.getText()).append('\n');
            if(x.getContentDescription()!=null)local.append(x.getContentDescription()).append('\n');
            Set<String> found=TextParser.phoneCandidates(local.toString());
            if(!found.isEmpty()){
                Rect r=bestBounds(x);if(!r.isEmpty()){
                    int td=Math.abs(r.centerY()-tr.centerY());int od=or==null?Integer.MAX_VALUE:Math.abs(r.centerY()-or.centerY());
                    if(td<=allowed&&td<od)rowPhones.addAll(found);
                }
            }
            for(int i=0;i<x.getChildCount();i++){AccessibilityNodeInfo c=x.getChild(i);if(c!=null)q.addLast(c);}
        }
        return rowPhones.size()==1?rowPhones.iterator().next():"";
    }

    public static Rect bestBounds(AccessibilityNodeInfo node){
        Rect r=new Rect();AccessibilityNodeInfo x=node;
        for(int i=0;x!=null&&i<5;i++){x.getBoundsInScreen(r);if(!r.isEmpty())return r;x=x.getParent();}
        return new Rect();
    }

    public static boolean clickMatchesCachedReceiverRow(AccessibilityNodeInfo source,int receiverY,int purchaserY){
        if(source==null||receiverY==Integer.MIN_VALUE)return false;
        Rect click=bestBounds(source);if(click.isEmpty())return false;
        int y=click.centerY();int rd=Math.abs(y-receiverY);
        if(purchaserY==Integer.MIN_VALUE)return rd<=180;
        int pd=Math.abs(y-purchaserY);int spacing=Math.max(1,Math.abs(receiverY-purchaserY));
        return rd<pd&&rd<=Math.max(90,(int)(spacing*0.70f));
    }

    public static String singleLikelyPhone(AccessibilityNodeInfo root,String eventText){
        LinkedHashSet<String> strong=new LinkedHashSet<>();LinkedHashSet<String> all=new LinkedHashSet<>();
        if(root!=null){
            ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int n=0;
            while(!q.isEmpty()&&n++<2600){
                AccessibilityNodeInfo x=q.removeFirst();StringBuilder local=new StringBuilder();
                if(x.getText()!=null)local.append(x.getText()).append('\n');
                if(x.getContentDescription()!=null)local.append(x.getContentDescription()).append('\n');
                Set<String> found=TextParser.phoneCandidates(local.toString());all.addAll(found);
                String cls=x.getClassName()==null?"":x.getClassName().toString().toLowerCase();
                String id=x.getViewIdResourceName()==null?"":x.getViewIdResourceName().toLowerCase();
                boolean inputLike=cls.contains("edittext")||id.contains("digit")||id.contains("dial")||id.contains("phone")||id.contains("number")||id.contains("input");
                if(inputLike)strong.addAll(found);
                for(int i=0;i<x.getChildCount();i++){AccessibilityNodeInfo c=x.getChild(i);if(c!=null)q.addLast(c);}
            }
        }
        if(eventText!=null)all.addAll(TextParser.phoneCandidates(eventText));
        if(strong.size()==1)return strong.iterator().next();
        if(strong.size()>1)return"";
        return all.size()==1?all.iterator().next():"";
    }

    private static boolean isActionable(AccessibilityNodeInfo x){
        if(x==null)return false;if(x.isClickable())return true;
        return x.getActionList()!=null&&x.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    private static boolean performClick(AccessibilityNodeInfo node){
        AccessibilityNodeInfo x=node;
        for(int i=0;x!=null&&i<7;i++){
            if((x.isClickable()||isActionable(x))&&x.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
            x=x.getParent();
        }
        return node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static String subtreeText(AccessibilityNodeInfo root,int limit){
        if(root==null)return"";StringBuilder b=new StringBuilder();ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int n=0;
        while(!q.isEmpty()&&n++<limit){
            AccessibilityNodeInfo x=q.removeFirst();append(b,x.getText());append(b,x.getContentDescription());
            for(int i=0;i<x.getChildCount();i++){AccessibilityNodeInfo c=x.getChild(i);if(c!=null)q.addLast(c);}
        }
        return b.toString();
    }

    // Notification trên Samsung/SystemUI thường tách câu và #mã thành nhiều node anh em.
    // v0.2.5 kiểm tra toàn subtree của card, không còn đòi một node chứa nguyên câu.
    public static boolean clickNotificationForOrder(AccessibilityNodeInfo root,String orderId){
        if(root==null||orderId==null||orderId.trim().isEmpty())return false;
        String needle="#"+orderId;
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int n=0;
        while(!q.isEmpty()&&n++<3500){
            AccessibilityNodeInfo x=q.removeFirst();String own="";
            if(x.getText()!=null)own+=x.getText().toString()+"\n";
            if(x.getContentDescription()!=null)own+=x.getContentDescription().toString()+"\n";
            if(own.contains(needle)||orderId.equals(TextParser.notificationId(own))){
                AccessibilityNodeInfo y=x;
                for(int i=0;y!=null&&i<9;i++){
                    String card=subtreeText(y,220);
                    if(orderId.equals(TextParser.notificationId(card))&&performClick(y))return true;
                    y=y.getParent();
                }
            }
            for(int i=0;i<x.getChildCount();i++){AccessibilityNodeInfo c=x.getChild(i);if(c!=null)q.addLast(c);}
        }
        // Pass 2: quét trực tiếp các card clickable vì có OEM không expose node #ID riêng.
        q.clear();q.add(root);n=0;
        while(!q.isEmpty()&&n++<3500){
            AccessibilityNodeInfo x=q.removeFirst();
            if(isActionable(x)){
                String card=subtreeText(x,260);
                if(orderId.equals(TextParser.notificationId(card))&&performClick(x))return true;
            }
            for(int i=0;i<x.getChildCount();i++){AccessibilityNodeInfo c=x.getChild(i);if(c!=null)q.addLast(c);}
        }
        return false;
    }
}
