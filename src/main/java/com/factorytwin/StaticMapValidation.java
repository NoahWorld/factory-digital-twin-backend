package com.factorytwin;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-side guard for the bounded, self-contained static map declaration. */
final class StaticMapValidation {
  private StaticMapValidation() {}
  private static final double EPS = 1e-7;
  private record Point(double x, double y) {}

  static SceneExtensions.Budget budget(JsonNode map) {
    if (map.isNull()) return SceneExtensions.ZERO;
    long meshes = 0, triangles = 0;
    for (JsonNode feature : map.path("features")) for (JsonNode polygon : feature.path("polygons")) {
      int points = polygon.path("outer").size() - 1;
      for (JsonNode hole : polygon.path("holes")) points += hole.size() - 1;
      long cap = points + 2L * polygon.path("holes").size() - 2;
      meshes += 2;
      triangles += feature.path("height").asDouble() > 0 ? cap * 2 + points * 2 : cap;
    }
    return new SceneExtensions.Budget(map.path("features").size(), meshes, triangles, 0);
  }

  static void validate(JsonNode map) {
    if (map.isNull()) return;
    Json.require(map.isObject() && map.toString().getBytes(StandardCharsets.UTF_8).length <= 512*1024,
        "staticMap must be an object of at most 512 KiB.");
    keys(map, "version", "id", "label", "visible", "coordinateSystem", "width", "outlineColor", "transform", "features");
    Json.require(map.path("version").isIntegralNumber() && map.path("version").asInt()==1,
        "staticMap version must be 1.");
    identifier(map.path("id")); printable(map.path("label"));
    Json.require(map.path("visible").isBoolean(), "staticMap visible must be boolean.");
    String system = map.path("coordinateSystem").asText("");
    Json.require(system.equals("local") || system.equals("wgs84"), "Invalid staticMap coordinate system.");
    Contracts.number(map.path("width"), .1, 10000, "staticMap width"); color(map.path("outlineColor"));
    JsonNode transform=map.path("transform"); keys(transform, "position", "rotation", "scale");
    vector3(transform.path("position"), -10000, 10000, false);
    vector3(transform.path("rotation"), -3600, 3600, false);
    vector3(transform.path("scale"), .001, 100, true);
    JsonNode features=map.path("features");
    Json.require(features.isArray() && features.size()>=1 && features.size()<=64,
        "staticMap requires 1..64 features.");
    int totalPoints=0;
    Set<String> ids=new HashSet<>();
    // Shape budgets precede all quadratic geometry checks.
    for (JsonNode feature:features) {
      keys(feature, "id", "label", "height", "color", "polygons");
      identifier(feature.path("id")); Json.require(ids.add(feature.path("id").asText()), "Duplicate map feature id.");
      printable(feature.path("label")); Contracts.number(feature.path("height"),0,1000,"feature height"); color(feature.path("color"));
      JsonNode polygons=feature.path("polygons");
      Json.require(polygons.isArray() && polygons.size()>=1 && polygons.size()<=16,
          "Map feature requires 1..16 polygons.");
      for (JsonNode polygon:polygons) {
        keys(polygon,"outer","holes");
        JsonNode holes=polygon.path("holes");
        Json.require(holes.isArray() && holes.size()<=8,"Map polygon exceeds 8 holes.");
        totalPoints=ringBudget(polygon.path("outer"),totalPoints);
        for (JsonNode hole:holes) totalPoints=ringBudget(hole,totalPoints);
      }
    }
    validateGeometry(features, system);
  }

  private static int ringBudget(JsonNode ring,int total) {
    Json.require(ring.isArray() && ring.size()>=4 && ring.size()<=257,"Map ring needs 4..257 closed points.");
    total+=ring.size(); Json.require(total<=4096,"staticMap exceeds 4096 points."); return total;
  }
  private record Ring(List<Point> points,double area,boolean hole,double minX,double minY,double maxX,double maxY) {}
  private record Polygon(Ring outer,List<Ring> holes) {}
  private static double cross(Point a,Point b,Point c) {return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
  private static double distance(Point a,Point b) {return Math.hypot(a.x-b.x,a.y-b.y);}
  private static boolean same(Point a,Point b) {return distance(a,b)<=EPS;}
  private static int side(Point a,Point b,Point c) {
    double distance=cross(a,b,c)/distance(a,b);
    return distance>EPS?1:distance< -EPS?-1:0;
  }
  private static boolean on(Point p,Point a,Point b) {
    return side(a,b,p)==0 && p.x>=Math.min(a.x,b.x)-EPS && p.x<=Math.max(a.x,b.x)+EPS
        && p.y>=Math.min(a.y,b.y)-EPS && p.y<=Math.max(a.y,b.y)+EPS;
  }
  private static boolean properCross(Point a,Point b,Point c,Point d) {
    return side(a,b,c)*side(a,b,d)<0 && side(c,d,a)*side(c,d,b)<0;
  }
  private static boolean meet(Point a,Point b,Point c,Point d) {
    if (Math.max(a.x,b.x)+EPS<Math.min(c.x,d.x) || Math.max(c.x,d.x)+EPS<Math.min(a.x,b.x)
        || Math.max(a.y,b.y)+EPS<Math.min(c.y,d.y) || Math.max(c.y,d.y)+EPS<Math.min(a.y,b.y)) return false;
    return properCross(a,b,c,d)||on(a,c,d)||on(b,c,d)||on(c,a,b)||on(d,a,b);
  }
  private static boolean boundsMeet(Ring a,Ring b) {
    return a.minX<=b.maxX+EPS && b.minX<=a.maxX+EPS && a.minY<=b.maxY+EPS && b.minY<=a.maxY+EPS;
  }
  private static boolean ringsMeet(Ring a,Ring b) {
    if (!boundsMeet(a,b)) return false;
    for (int i=0;i<a.points.size();i++) for (int j=0;j<b.points.size();j++)
      if (meet(a.points.get(i),a.points.get((i+1)%a.points.size()),
          b.points.get(j),b.points.get((j+1)%b.points.size()))) return true;
    return false;
  }
  private static int inRing(Point p,Ring ring) {
    boolean inside=false;
    for (int i=0;i<ring.points.size();i++) {
      Point a=ring.points.get(i),b=ring.points.get((i+1)%ring.points.size());
      if (on(p,a,b)) return 0;
      if ((a.y>p.y)!=(b.y>p.y) && p.x<a.x+(p.y-a.y)*(b.x-a.x)/(b.y-a.y)) inside=!inside;
    }
    return inside?1:-1;
  }
  private static int inPolygon(Point p,Polygon polygon) {
    int outer=inRing(p,polygon.outer);
    if (outer!=1) return outer;
    for (Ring hole:polygon.holes) {
      int inside=inRing(p,hole);
      if (inside==0) return 0;
      if (inside==1) return -1;
    }
    return 1;
  }
  private static Ring validRing(List<Point> points,boolean hole) {
    double area=0,perimeter=0,minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY;
    double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
    int n=points.size();
    for (int i=0;i<n;i++) {
      Point a=points.get(i),b=points.get((i+1)%n),previous=points.get((i+n-1)%n);
      Json.require(!same(a,b),"Map ring has duplicate or too-close adjacent points.");
      double dot=(previous.x-a.x)*(b.x-a.x)+(previous.y-a.y)*(b.y-a.y);
      Json.require(!(side(previous,a,b)==0 && dot>0),"Map ring backtracks along an edge.");
      area+=cross(points.getFirst(),a,b)/2;
      perimeter+=distance(a,b);
      minX=Math.min(minX,a.x); minY=Math.min(minY,a.y);
      maxX=Math.max(maxX,a.x); maxY=Math.max(maxY,a.y);
    }
    Json.require(Math.abs(area)>EPS*perimeter,"Map ring is degenerate at scene scale.");
    for (int i=0;i<n;i++) for (int j=i+1;j<n;j++) {
      if (j==i+1 || (i==0 && j==n-1)) continue;
      Json.require(!meet(points.get(i),points.get((i+1)%n),points.get(j),points.get((j+1)%n)),
          "Map ring self-intersects or touches itself.");
    }
    return new Ring(points,area,hole,minX,minY,maxX,maxY);
  }
  private static double positive(double angle) {
    double tau=2*Math.PI;
    return (angle%tau+tau)%tau;
  }
  private static double[] filledArc(Ring ring,int edge,Point p) {
    int n=ring.points.size(); Point a=ring.points.get(edge),b=ring.points.get((edge+1)%n);
    Point previous=a,next=b;
    if (same(p,a)) { previous=ring.points.get((edge+n-1)%n); next=b; }
    else if (same(p,b)) { previous=a; next=ring.points.get((edge+2)%n); }
    double prevAngle=Math.atan2(previous.y-p.y,previous.x-p.x), nextAngle=Math.atan2(next.y-p.y,next.x-p.x);
    boolean filledLeft=(ring.area>0)!=ring.hole;
    double start=positive(filledLeft?nextAngle:prevAngle);
    return new double[]{start,start+positive((filledLeft?prevAngle:nextAngle)-start)};
  }
  private static boolean arcsOverlap(double[] a,double[] b) {
    double tau=2*Math.PI;
    for (int offset=-1;offset<=1;offset++)
      if (Math.min(a[1],b[1]+offset*tau)-Math.max(a[0],b[0]+offset*tau)>EPS) return true;
    return false;
  }
  private static boolean polygonsOverlap(Polygon a,Polygon b) {
    if (!boundsMeet(a.outer,b.outer)) return false;
    List<Ring> ar=new ArrayList<>(),br=new ArrayList<>();
    ar.add(a.outer);ar.addAll(a.holes);br.add(b.outer);br.addAll(b.holes);
    for (Ring ra:ar) for (Ring rb:br) {
      if (!boundsMeet(ra,rb)) continue;
      for (int i=0;i<ra.points.size();i++) for (int j=0;j<rb.points.size();j++) {
        Point a0=ra.points.get(i),a1=ra.points.get((i+1)%ra.points.size());
        Point b0=rb.points.get(j),b1=rb.points.get((j+1)%rb.points.size());
        if (!meet(a0,a1,b0,b1)) continue;
        if (properCross(a0,a1,b0,b1)) return true;
        for (Point p:List.of(a0,a1,b0,b1))
          if (on(p,a0,a1)&&on(p,b0,b1)&&arcsOverlap(filledArc(ra,i,p),filledArc(rb,j,p))) return true;
      }
    }
    return inPolygon(a.outer.points.getFirst(),b)==1 || inPolygon(b.outer.points.getFirst(),a)==1;
  }
  private static List<Point> rawRing(JsonNode raw,String system) {
    List<Point> points=new ArrayList<>(raw.size());
    for (JsonNode item:raw) {
      Json.require(item.isArray()&&item.size()==2,"Map point needs two coordinates.");
      Contracts.number(item.get(0),system.equals("local")?-10000:-180,system.equals("local")?10000:180,"map x/longitude");
      Contracts.number(item.get(1),system.equals("local")?-10000:-85,system.equals("local")?10000:85,"map y/latitude");
      points.add(new Point(item.get(0).asDouble(),item.get(1).asDouble()));
    }
    Json.require(points.getFirst().equals(points.getLast()),"Map ring must be exactly closed.");
    return points;
  }
  private static void validateGeometry(JsonNode features,String system) {
    List<List<List<List<Point>>>> source=new ArrayList<>();
    double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
    for (JsonNode feature:features) {
      List<List<List<Point>>> featurePolygons=new ArrayList<>();
      for (JsonNode polygon:feature.path("polygons")) {
        List<List<Point>> rings=new ArrayList<>();
        rings.add(rawRing(polygon.path("outer"),system));
        for (JsonNode hole:polygon.path("holes")) rings.add(rawRing(hole,system));
        for (List<Point> ring:rings) for (Point p:ring) {
          minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);
        }
        featurePolygons.add(rings);
      }
      source.add(featurePolygons);
    }
    Json.require(!system.equals("wgs84")||maxX-minX<=180,"WGS84 map crosses the date line.");
    double cx=(minX+maxX)/2,cy=(minY+maxY)/2;
    double longitudeScale=system.equals("wgs84")?Math.cos(cy*Math.PI/180):1;
    double extent=Math.max((maxX-minX)*longitudeScale,maxY-minY);
    Json.require(extent>0,"Map has zero planar extent.");
    for (List<List<List<Point>>> feature:source) {
      List<Polygon> accepted=new ArrayList<>();
      for (List<List<Point>> raw:feature) {
        List<Ring> rings=new ArrayList<>();
        for (int i=0;i<raw.size();i++) {
          List<Point> normalized=new ArrayList<>();
          for (int j=0;j<raw.get(i).size()-1;j++) {
            Point p=raw.get(i).get(j);
            normalized.add(new Point((p.x-cx)*longitudeScale/extent,(p.y-cy)/extent));
          }
          rings.add(validRing(normalized,i>0));
        }
        Ring outer=rings.getFirst();List<Ring> holes=rings.subList(1,rings.size());
        for (int i=0;i<holes.size();i++) {
          Ring hole=holes.get(i);
          Json.require(!ringsMeet(outer,hole)&&inRing(hole.points.getFirst(),outer)==1,
              "Map hole must lie strictly inside outer ring.");
          for (int j=0;j<i;j++) Json.require(!ringsMeet(hole,holes.get(j))
              && inRing(hole.points.getFirst(),holes.get(j))==-1
              && inRing(holes.get(j).points.getFirst(),hole)==-1,"Map holes overlap, nest or touch.");
        }
        Polygon polygon=new Polygon(outer,List.copyOf(holes));
        for (Polygon prior:accepted) Json.require(!polygonsOverlap(polygon,prior),"Map feature polygons overlap.");
        accepted.add(polygon);
      }
    }
  }
  private static void keys(JsonNode node,String...fields) {
    Json.require(node.isObject()&&node.size()==fields.length,"Map has missing or unknown fields.");
    for (String field:fields) Json.require(node.has(field),"Map missing "+field+".");
  }
  private static void identifier(JsonNode node) {
    Json.require(node.isTextual() && node.asText().length()<=120
        && node.asText().matches("[A-Za-z0-9][A-Za-z0-9._:-]*"),"Invalid map identifier.");
  }
  private static void printable(JsonNode node) {
    Json.require(node.isTextual() && !node.asText().isBlank() && node.asText().length()<=80
        && node.asText().codePoints().noneMatch(c->c<32||(c>=127&&c<=159)),"Invalid map label.");
  }
  private static void color(JsonNode node) {
    Json.require(node.isTextual() && node.asText().matches("#[0-9a-fA-F]{6}"),"Invalid map color.");
  }
  private static void vector3(JsonNode node,double min,double max,boolean exclusiveMin) {
    Json.require(node.isArray()&&node.size()==3,"Map transform needs three coordinates.");
    for (JsonNode value:node) {
      Contracts.number(value,min,max,"map transform");
      Json.require(!exclusiveMin || value.asDouble()>min,"Map scale must exceed minimum.");
    }
  }
}
