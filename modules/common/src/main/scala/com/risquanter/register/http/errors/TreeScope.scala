package com.risquanter.register.http.errors

import com.risquanter.register.domain.data.iron.TreeId

/** Which tree a failure is being projected on.
  *
  * An `AppError` carries no tree, and adding one would mean a tree identifier
  * at every construction site, including the ones where none is in scope. The
  * controller calling the projection has the tree in its own handler, so it
  * states the scope at that call site instead.
  *
  * It never crosses the wire. It is the projection's input, and the two
  * rejection cases it selects between carry the outcome instead:
  * `ValidationRejected` holds a plain `TreeId`, and `RequestRejected` holds no
  * tree at all.
  */
enum TreeScope:
  case InTree(tree: TreeId)
  case NoTree
