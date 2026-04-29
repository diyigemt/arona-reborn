// arona-core + kivotos: BSON DOUBLE -> Int (Int32 / Int64) 数据迁移
//
// 起因: bson-kotlinx 的 KotlinSerializerCodec 严格类型校验, 不接受 DOUBLE 解码到 Int / Long.
// 历史数据由旧反射 codec 写入时部分数字字段被存为 DOUBLE, 切到 kotlinx codec 后无法读取.
//
// 适用 collections (跨两个 db, 各 collection 仅在所属 db 处理, 其它 db 自动跳过):
//   kivotos db:
//     - CoffeeTouch         : level / students / touchedStudents / tendencyStudent  ($toInt → Int32)
//     - FavorLevelExcelTable: _id (= level) / sum / next                            ($toInt → Int32)
//   arona 主库:
//     - User                : qq                                                    ($toLong → Int64)
//     - SystemSequence      : seq                                                   ($toLong → Int64)
//
// 用法 (确保应用已停止, 避免并发写丢数据); 需要分别对两个 db 各跑一次:
//   mongosh "mongodb://USER:PASS@HOST:PORT/kivotos?authSource=kivotos" \
//           --file scripts/migration/fix-bson-double-to-int.js
//   mongosh "mongodb://USER:PASS@HOST:PORT/arona?authSource=arona" \
//           --file scripts/migration/fix-bson-double-to-int.js
//
// 幂等: 重复执行无副作用. $toInt 对已是 INT32 的字段是 no-op, $toLong 对已是 INT64 的字段是 no-op.

print("=== arona-core + kivotos: BSON DOUBLE -> Int (Int32 / Int64) 数据迁移 ===");
print("数据库: " + db.getName());

// 当前 db 不存在某 collection 时, 该步骤跳过, 让脚本可在 kivotos / arona 两个 db 上下文都安全跑.
const existingCollections = new Set(db.getCollectionNames());
function hasCollection(name) {
  return existingCollections.has(name);
}

// ============================================================================
// 1. CoffeeTouch (kivotos db)
// schema: {_id:String, level:Int, students:List<Int>, touchedStudents:List<Int>,
//          tendencyStudent:List<Int>, ...string字段不动}
// _id 是 String, 无需 delete+insert.
// ============================================================================

print("\n[1/4] CoffeeTouch (kivotos)");
if (!hasCollection("CoffeeTouch")) {
  print("  当前 db 不存在 CoffeeTouch, 跳过 (在 arona 主库下属正常情况).");
} else {
  const coffeeFilter = {
    $or: [
      { level: { $type: "double" } },
      { students: { $elemMatch: { $type: "double" } } },
      { touchedStudents: { $elemMatch: { $type: "double" } } },
      { tendencyStudent: { $elemMatch: { $type: "double" } } },
    ],
  };
  const coffeeBefore = db.CoffeeTouch.countDocuments(coffeeFilter);
  print("  before: " + coffeeBefore + " 个文档存在 DOUBLE 字段");

  const coffeeRes = db.CoffeeTouch.updateMany(
    {},
    [
      {
        $set: {
          level: { $toInt: "$level" },
          students: {
            $map: {
              input: { $ifNull: ["$students", []] },
              as: "s",
              in: { $toInt: "$$s" },
            },
          },
          touchedStudents: {
            $map: {
              input: { $ifNull: ["$touchedStudents", []] },
              as: "s",
              in: { $toInt: "$$s" },
            },
          },
          tendencyStudent: {
            $map: {
              input: { $ifNull: ["$tendencyStudent", []] },
              as: "s",
              in: { $toInt: "$$s" },
            },
          },
        },
      },
    ],
  );
  print("  matched: " + coffeeRes.matchedCount + ", modified: " + coffeeRes.modifiedCount);

  const coffeeAfter = db.CoffeeTouch.countDocuments(coffeeFilter);
  print("  after: " + coffeeAfter + " (期望 0)");
  if (coffeeAfter !== 0) {
    print("  ⚠️ CoffeeTouch 仍有残留 DOUBLE 字段, 检查!");
  }
}

// ============================================================================
// 2. FavorLevelExcelTable (kivotos db)
// schema: {_id:Int (= level), sum:Int, next:Int}
// _id 是 Int, MongoDB 禁止 updateMany 修改 _id, 必须 delete + insert 重写.
// ============================================================================

print("\n[2/4] FavorLevelExcelTable (kivotos)");
if (!hasCollection("FavorLevelExcelTable")) {
  print("  当前 db 不存在 FavorLevelExcelTable, 跳过 (在 arona 主库下属正常情况).");
} else {
  // step 2.1: _id 类型为 DOUBLE 的文档 - delete + insert
  const idDoubleDocs = db.FavorLevelExcelTable.find({ _id: { $type: "double" } }).toArray();
  print("  _id 为 DOUBLE 的文档: " + idDoubleDocs.length);

  if (idDoubleDocs.length > 0) {
    // 检查转换后的 _id 是否会与现有 INT _id 冲突
    const conflicts = idDoubleDocs.filter(function (d) {
      const newId = NumberInt(d._id);
      return db.FavorLevelExcelTable.countDocuments({ _id: newId }) > 0
        && Number(d._id) !== Number(newId);
    });
    if (conflicts.length > 0) {
      print("  ⚠️ 检测到 _id 冲突, 中止以免丢数据:");
      conflicts.forEach(function (d) { print("    " + JSON.stringify(d)); });
      throw new Error("_id collision after DOUBLE -> INT cast");
    }

    let migrated = 0;
    idDoubleDocs.forEach(function (doc) {
      const newDoc = Object.assign({}, doc, {
        _id: NumberInt(doc._id),
        sum: NumberInt(doc.sum),
        next: NumberInt(doc.next),
      });
      const delRes = db.FavorLevelExcelTable.deleteOne({ _id: doc._id });
      if (delRes.deletedCount !== 1) {
        print("  ⚠️ deleteOne 失败 _id=" + doc._id + ", 跳过此条");
        return;
      }
      db.FavorLevelExcelTable.insertOne(newDoc);
      migrated++;
    });
    print("  delete+insert 完成: " + migrated + " 条");
  }

  // step 2.2: _id 已是 INT 但 sum/next 仍是 DOUBLE - updateMany 直接转
  const sumNextFilter = {
    $or: [
      { sum: { $type: "double" } },
      { next: { $type: "double" } },
    ],
  };
  const sumNextBefore = db.FavorLevelExcelTable.countDocuments(sumNextFilter);
  print("  sum/next 为 DOUBLE 的文档: " + sumNextBefore);

  if (sumNextBefore > 0) {
    const favorRes = db.FavorLevelExcelTable.updateMany(
      sumNextFilter,
      [{ $set: { sum: { $toInt: "$sum" }, next: { $toInt: "$next" } } }],
    );
    print("  matched: " + favorRes.matchedCount + ", modified: " + favorRes.modifiedCount);
  }

  const favorAfter = db.FavorLevelExcelTable.countDocuments({
    $or: [
      { _id: { $type: "double" } },
      { sum: { $type: "double" } },
      { next: { $type: "double" } },
    ],
  });
  print("  after: " + favorAfter + " (期望 0)");
  if (favorAfter !== 0) {
    print("  ⚠️ FavorLevelExcelTable 仍有残留 DOUBLE 字段, 检查!");
  }
}

// ============================================================================
// 3. User (arona 主库)
// schema: {_id:String, qq:Long, ...}. _id 是 String, 数字字段仅 qq.
// ============================================================================

print("\n[3/4] User (arona)");
if (!hasCollection("User")) {
  print("  当前 db 不存在 User, 跳过 (在 kivotos db 下属正常情况).");
} else {
  const userFilter = { qq: { $type: "double" } };
  const userBefore = db.User.countDocuments(userFilter);
  print("  before: " + userBefore + " 个文档存在 DOUBLE qq");
  if (userBefore > 0) {
    const userRes = db.User.updateMany(
      userFilter,
      [{ $set: { qq: { $toLong: "$qq" } } }],
    );
    print("  matched: " + userRes.matchedCount + ", modified: " + userRes.modifiedCount);
  }
  const userAfter = db.User.countDocuments(userFilter);
  print("  after: " + userAfter + " (期望 0)");
  if (userAfter !== 0) {
    print("  ⚠️ User.qq 仍有残留 DOUBLE, 检查!");
  }
}

// ============================================================================
// 4. SystemSequence (arona 主库)
// schema: {_id:String (BASE_ID_KEY), seq:Long}
// ============================================================================

print("\n[4/4] SystemSequence (arona)");
if (!hasCollection("SystemSequence")) {
  print("  当前 db 不存在 SystemSequence, 跳过 (在 kivotos db 下属正常情况).");
} else {
  const seqFilter = { seq: { $type: "double" } };
  const seqBefore = db.SystemSequence.countDocuments(seqFilter);
  print("  before: " + seqBefore + " 个文档存在 DOUBLE seq");
  if (seqBefore > 0) {
    const seqRes = db.SystemSequence.updateMany(
      seqFilter,
      [{ $set: { seq: { $toLong: "$seq" } } }],
    );
    print("  matched: " + seqRes.matchedCount + ", modified: " + seqRes.modifiedCount);
  }
  const seqAfter = db.SystemSequence.countDocuments(seqFilter);
  print("  after: " + seqAfter + " (期望 0)");
  if (seqAfter !== 0) {
    print("  ⚠️ SystemSequence.seq 仍有残留 DOUBLE, 检查!");
  }
}

print("\n=== 完成 ===");
