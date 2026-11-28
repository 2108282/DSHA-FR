#!/usr/bin/env python3
"""Run structural regressions against extracted production dialog-layout methods.

This compiles the current Java methods with tiny in-memory View stubs. It does
not emulate Android measure/layout and makes no claim about rendered dimensions.
"""
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/deepseekharness/app/ui/DshaDialogBuilder.java"
RES = ROOT / "app/src/main/res/layout"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def extract_method(source, name):
    match = re.search(r"(?:private|protected|public)\s+(?:static\s+)?[\w.<>?]+\s+" +
                      re.escape(name) + r"\s*\([^)]*\)\s*\{", source)
    if not match:
        raise AssertionError(f"production method not found: {name}")
    start = source.index("{", match.start())
    depth, i, state = 0, start, "code"
    while i < len(source):
        ch = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
        if state == "code":
            if ch == '"': state = "string"
            elif ch == "'": state = "char"
            elif ch == "/" and nxt == "/": state = "line"
            elif ch == "/" and nxt == "*": state = "block"; i += 1
            elif ch == "{": depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return source[match.start():i + 1]
        elif state in ("string", "char"):
            if ch == "\\": i += 1
            elif (state == "string" and ch == '"') or (state == "char" and ch == "'"):
                state = "code"
        elif state == "line" and ch == "\n": state = "code"
        elif state == "block" and ch == "*" and nxt == "/": state = "code"; i += 1
        i += 1
    raise AssertionError(f"unclosed production method: {name}")


def java_dimension(value, default=-2):
    if not value:
        return default
    if value == "wrap_content":
        return -2
    if value == "match_parent":
        return -1
    if value.endswith("dp") or value.endswith("dip"):
        try:
            return int(float(value.rstrip("dip")))
        except ValueError:
            return 0
    return 0


def java_string(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def fixture_method(xml_path, method_name):
    root = ET.parse(xml_path).getroot()
    lines = [f"  private static View {method_name}(Context context) {{"]
    refs, counter = {}, [0]

    def emit(node, parent=None):
        index = counter[0]
        counter[0] += 1
        ref = f"v{index}"
        refs[node] = ref
        tag = node.tag.rsplit("}", 1)[-1].rsplit(".", 1)[-1]
        if tag in ("NestedScrollView", "ScrollView", "FrameLayout", "ModernCardView",
                   "LinearLayout", "RadioGroup", "Button", "RadioButton", "CheckBox",
                   "CompoundButton", "TextView", "EditText", "ImageView"):
            cls = tag
        elif list(node):
            cls = "ViewGroup"
        else:
            cls = "View"
        lines.append(f"    {cls} {ref} = new {cls}();")
        view_id = node.get(ANDROID + "id", "")
        if view_id:
            lines.append(f"    {ref}.setTag({java_string(view_id.rsplit('/', 1)[-1])});")
        orientation = node.get(ANDROID + "orientation")
        if cls in ("LinearLayout", "ModernCardView"):
            orient = "VERTICAL" if orientation == "vertical" else "HORIZONTAL"
            lines.append(f"    {ref}.setOrientation(LinearLayout.{orient});")
        if parent is not None:
            parent_cls = parent.tag.rsplit("}", 1)[-1].rsplit(".", 1)[-1]
            width = java_dimension(node.get(ANDROID + "layout_width"))
            height = java_dimension(node.get(ANDROID + "layout_height"))
            weight = node.get(ANDROID + "layout_weight", "0")
            try:
                float(weight)
            except ValueError:
                weight = "0"
            if parent_cls in ("LinearLayout", "ModernCardView", "RadioGroup"):
                params = f"new LinearLayout.LayoutParams({width}, {height}, {weight}f)"
            else:
                params = f"new FrameLayout.LayoutParams({width}, {height})"
            lines.append(f"    {refs[parent]}.addView({ref}, {params});")
        for child in node:
            emit(child, node)
        return ref

    root_ref = emit(root)
    lines += [f"    return {root_ref};", "  }"]
    return "\n".join(lines)


STUBS = r'''import java.util.*;
class Context {}
class View {
  static final int OVER_SCROLL_NEVER=2;
  private ViewGroup.LayoutParams params; private ViewGroup parent; private String tag;
  ViewGroup.LayoutParams getLayoutParams() { return params; } ViewGroup getParent(){return parent;} void setParent(ViewGroup p){parent=p;}
  void setLayoutParams(ViewGroup.LayoutParams p) { params = p; }
  void setTag(String t) { tag = t; } String getTag() { return tag; }
}
class ViewGroup extends View {
  static class LayoutParams {
    static final int MATCH_PARENT=-1, WRAP_CONTENT=-2;
    final int width, height; LayoutParams(int w,int h){width=w;height=h;}
  }
  final ArrayList<View> children = new ArrayList<>();
  int getChildCount(){return children.size();} View getChildAt(int i){return children.get(i);}
  void addView(View v){ addView(v,new LayoutParams(-2,-2)); }
  void addView(View v, LayoutParams p){ addView(v,children.size(),p); }
  void addView(View v,int i,LayoutParams p){ children.add(i,v); v.setLayoutParams(p); v.setParent(this); }
  void removeViewAt(int i){children.remove(i).setParent(null);}
}
class FrameLayout extends ViewGroup {
  static class LayoutParams extends ViewGroup.LayoutParams { LayoutParams(int w,int h){super(w,h);} }
}
class LinearLayout extends ViewGroup {
  LinearLayout(){} LinearLayout(Context c){}
  static final int HORIZONTAL=0, VERTICAL=1; private int orientation=HORIZONTAL;
  int getOrientation(){return orientation;} void setOrientation(int o){orientation=o;}
  static class LayoutParams extends ViewGroup.LayoutParams {
    final float weight; LayoutParams(int w,int h){this(w,h,0f);} LayoutParams(int w,int h,float x){super(w,h);weight=x;}
  }
}
class RadioGroup extends LinearLayout { RadioGroup(){setOrientation(VERTICAL);} }
class ScrollView extends FrameLayout {}
class NestedScrollView extends FrameLayout {
  NestedScrollView(){} NestedScrollView(Context c){}
  void setFillViewport(boolean b){} void setOverScrollMode(int m){} void setVerticalScrollBarEnabled(boolean b){}
}
class Button extends View {}
class CompoundButton extends Button {}
class RadioButton extends CompoundButton {}
class CheckBox extends CompoundButton {}
class TextView extends View {}
class EditText extends TextView {}
class ImageView extends View {}
class ModernCardView extends LinearLayout {}
'''


def main_source(methods, fixture_methods):
    return STUBS + "\nclass DialogLayoutRegression {\n" + "\n".join(methods) + "\n" + \
        "\n".join(fixture_methods) + r'''
  private final Context context = new Context();
  private static void check(boolean ok, String message){if(!ok)throw new AssertionError(message);}
  private static View find(View v,String tag){
    if(tag.equals(v.getTag())) return v;
    if(v instanceof ViewGroup) for(View c:((ViewGroup)v).children){View f=find(c,tag);if(f!=null)return f;}
    return null;
  }
  private static List<View> tree(View v){
    ArrayList<View> out=new ArrayList<>(); out.add(v);
    if(v instanceof ViewGroup) for(View c:((ViewGroup)v).children)out.addAll(tree(c));
    return out;
  }
  private static final class Snapshot {
    final ArrayList<View> views=new ArrayList<>();
    final ArrayList<ViewGroup> parents=new ArrayList<>();
    final ArrayList<ViewGroup.LayoutParams> params=new ArrayList<>();
    Snapshot(View root){capture(root,null);}
    private void capture(View v,ViewGroup parent){views.add(v);parents.add(parent);params.add(v.getLayoutParams());
      if(v instanceof ViewGroup)for(View c:((ViewGroup)v).children)capture(c,(ViewGroup)v);}
  }
  private static void sameTree(Snapshot before,View root,String label){
    Snapshot after=new Snapshot(root); check(before.views.size()==after.views.size(),label+" child count changed");
    for(int i=0;i<before.views.size();i++){
      check(before.views.get(i)==after.views.get(i),label+" identity/order changed at "+i);
      check(before.parents.get(i)==after.parents.get(i),label+" parent changed at "+i);
      check(before.params.get(i)==after.params.get(i),label+" layout params changed at "+i);
    }
  }
  private static ModernCardView card(View root){
    for(View v:tree(root)) if(v instanceof ModernCardView)return (ModernCardView)v;
    throw new AssertionError("fixture has no ModernCardView");
  }
  private void installPreviewUsesFixedHeadingAndActions(){
    View root=installPreviewFixture(context); ModernCardView c=card(root);
    View[] original=c.children.toArray(new View[0]);
    View[] body={original[1],original[2],original[3]};
    ViewGroup.LayoutParams[] params={body[0].getLayoutParams(),body[1].getLayoutParams(),body[2].getLayoutParams()};
    prepareCardLayout(root);
    check(c.getChildCount()==3,"install preview should become heading/scroll/footer");
    check(c.getChildAt(0)==original[0],"install heading identity changed");
    check(c.getChildAt(2)==original[4],"install footer row identity changed");
    check(c.getChildAt(1) instanceof NestedScrollView,"install body is not a NestedScrollView");
    NestedScrollView scroll=(NestedScrollView)c.getChildAt(1);
    LinearLayout.LayoutParams scrollParams=(LinearLayout.LayoutParams)scroll.getLayoutParams();
    check(scrollParams.weight>0 && scrollParams.height==ViewGroup.LayoutParams.WRAP_CONTENT,"scroll must use weighted wrap_content params");
    LinearLayout moved=(LinearLayout)scroll.getChildAt(0);
    check(moved.getChildCount()==body.length,"all preview body rows should be inside one scroll");
    for(int i=0;i<body.length;i++){
      check(moved.getChildAt(i)==body[i],"body row identity changed");
      check(body[i].getLayoutParams()==params[i],"body layout params were not preserved");
    }
    check(find(root,"btnDialogCancel")!=null && find(root,"btnDialogConfirm")!=null,"original preview buttons no longer findable");
    Snapshot once=new Snapshot(root); prepareCardLayout(root); sameTree(once,root,"preview idempotence");
  }
  private void realScrollableDialogsStayUntouched(){
    Fixture[] fixtures={CREATEWORKSPACEFIXTURE_FIXTURE,MODELDETAILFIXTURE_FIXTURE,PLUGINEXPORTFIXTURE_FIXTURE,MODELADVANCEDFIXTURE_FIXTURE,FREEZELOGFIXTURE_FIXTURE};
    String[] names={"create_workspace","model_detail","plugin_export","model_advanced","freeze_log"};
    for(int i=0;i<fixtures.length;i++){
      View root=fixtures[i].make(context); Snapshot before=new Snapshot(root);
      prepareCardLayout(root); sameTree(before,root,names[i]);
    }
  }
  private void multipleFooterRowsAndBodiesStaySiblings(){
    ModernCardView c=new ModernCardView(); c.setOrientation(LinearLayout.VERTICAL);
    TextView head=new TextView(), a=new TextView(), b=new TextView();
    LinearLayout f1=buttonRow(), f2=buttonRow();
    c.addView(head,new LinearLayout.LayoutParams(-1,-2));
    c.addView(a,new LinearLayout.LayoutParams(-1,37)); c.addView(b,new LinearLayout.LayoutParams(-1,41));
    c.addView(f1,new LinearLayout.LayoutParams(-1,-2)); c.addView(f2,new LinearLayout.LayoutParams(-1,-2));
    prepareCardLayout(c);
    check(c.getChildCount()==4 && c.getChildAt(0)==head && c.getChildAt(2)==f1 && c.getChildAt(3)==f2,"multiple action rows not retained as siblings");
    NestedScrollView s=(NestedScrollView)c.getChildAt(1); LinearLayout body=(LinearLayout)s.getChildAt(0);
    check(body.getChildCount()==2 && body.getChildAt(0)==a && body.getChildAt(1)==b,"multiple body items not collected");
    check(a.getLayoutParams().height==37 && b.getLayoutParams().height==41,"body params changed");
  }
  private static LinearLayout buttonRow(){
    LinearLayout row=new LinearLayout(); row.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(new Button(),new LinearLayout.LayoutParams(0,44,1f)); return row;
  }
  private void wrapperAndCardBoundaries(){
    ModernCardView single=new ModernCardView(); single.setOrientation(LinearLayout.VERTICAL);
    single.addView(new TextView()); single.addView(new TextView()); single.addView(buttonRow());
    FrameLayout one=new FrameLayout(); one.addView(single,new FrameLayout.LayoutParams(-1,-2));
    prepareCardLayout(one); check(single.getChildAt(1) instanceof NestedScrollView,"single-child frame wrapper should reach outer card");

    ModernCardView hidden=new ModernCardView(); hidden.setOrientation(LinearLayout.VERTICAL);
    hidden.addView(new TextView()); hidden.addView(new TextView()); hidden.addView(buttonRow());
    FrameLayout many=new FrameLayout(); many.addView(hidden); many.addView(new TextView());
    Snapshot before=new Snapshot(many); prepareCardLayout(many); sameTree(before,many,"multi-child frame wrapper");

    ModernCardView embedded=new ModernCardView(); embedded.setOrientation(LinearLayout.VERTICAL);
    embedded.addView(new TextView()); embedded.addView(new TextView()); embedded.addView(buttonRow());
    LinearLayout form=new LinearLayout(); form.addView(embedded); Snapshot formBefore=new Snapshot(form);
    prepareCardLayout(form); sameTree(formBefore,form,"embedded card in LinearLayout");
  }
  private void nonActionFormAndNonVerticalCardStayUnchanged(){
    for(boolean vertical:new boolean[]{true,false}){
      ModernCardView c=new ModernCardView(); c.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
      c.addView(new TextView()); c.addView(new TextView());
      if(vertical){LinearLayout row=new LinearLayout();row.addView(new CheckBox());row.addView(new RadioButton());c.addView(row);}
      else c.addView(buttonRow());
      Snapshot before=new Snapshot(c); prepareCardLayout(c); sameTree(before,c,"non-action/non-vertical card");
    }
  }
  public static void main(String[] args){
    DialogLayoutRegression t=new DialogLayoutRegression();
    t.installPreviewUsesFixedHeadingAndActions(); t.realScrollableDialogsStayUntouched();
    t.multipleFooterRowsAndBodiesStaySiblings(); t.wrapperAndCardBoundaries();
    t.nonActionFormAndNonVerticalCardStayUnchanged();
    System.out.println("Dialog layout structure tests passed (production methods + XML fixtures; no Android measurement).");
  }
}
'''


def main():
    java_source = JAVA.read_text(encoding="utf-8")
    set_view = extract_method(java_source, "setView")
    hook_at = set_view.find("prepareCardLayout(customView)")
    constrain_at = set_view.find("constrainHeight(customView)")
    if hook_at < 0 or constrain_at < 0 or hook_at >= constrain_at:
        raise AssertionError("setView(View) must prepare the card before height constraint")
    methods = [extract_method(java_source, name) for name in
               ("prepareCardLayout", "prepareCardBody", "isActionSection", "containsScrollContainer")]
    fixture_files = [
        ("installPreviewFixture", "dialog_plugin_install_preview.xml"),
        ("createWorkspaceFixture", "dialog_create_workspace.xml"),
        ("modelDetailFixture", "dialog_model_detail.xml"),
        ("pluginExportFixture", "dialog_plugin_export.xml"),
        ("modelAdvancedFixture", "dialog_model_advanced.xml"),
        ("freezeLogFixture", "dialog_freeze_log.xml"),
    ]
    factories = [fixture_method(RES / filename, name) for name, filename in fixture_files]
    # A small adapter lets the existing-scroll fixtures share one test loop.
    adapter = r'''  private interface Fixture { View make(Context c); }
'''
    for name, _ in fixture_files[1:]:
        adapter += f"  private static final Fixture {name.upper()}_FIXTURE = c -> {name}(c);\n"
    # The install fixture is called directly; all generated methods use source XML.
    java = main_source(methods, factories)
    java = java.replace("class DialogLayoutRegression {", "class DialogLayoutRegression {\n" + adapter)
    with tempfile.TemporaryDirectory(prefix="dsha-dialog-layout-") as tmp:
        src = Path(tmp) / "DialogLayoutRegression.java"
        src.write_text(java, encoding="utf-8")
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", tmp, str(src)], check=True)
        subprocess.run(["java", "-cp", tmp, "DialogLayoutRegression"], check=True)


if __name__ == "__main__":
    main()
