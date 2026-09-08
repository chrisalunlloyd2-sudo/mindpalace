package com.mindpalace.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * FrustumCuller (perf: frustum culling) — extracts the 6 view-frustum planes
 * from proj * view once per frame (Gribb-Hartmann) and tests AABBs.
 *
 * Replaces WorldBuilder's 2D "isInFront" approximation for rooms: a room
 * box fully outside ANY plane skips its geometry walk entirely. The F4
 * FPS overlay measures the delta; hallway proximity culling stays.
 *
 * JOML is column-major: e[col*4 + row]. Row k of the combined matrix is
 * (e[k], e[4+k], e[8+k], e[12+k]); row3 = (e[3], e[7], e[11], e[15]).
 * left=row3+row0, right=row3-row0, bottom=row3+row1, top=row3-row1,
 * near=row3+row2, far=row3-row2.
 */
public final class FrustumCuller {

    // plane i: a*x + b*y + c*z + d > 0 → inside
    private final float[][] planes = new float[6][4];
    private final Matrix4f m = new Matrix4f();

    /** Call once per frame with the current matrices. */
    public void update(Matrix4f proj, Matrix4f view) {
        m.set(proj).mul(view);
        float[] e = new float[16];
        m.get(e);
        setPlane(0, e,  0); // left
        setPlane(1, e, -0); // right  (sign -1 on row0)
        setPlane(2, e,  1); // bottom
        setPlane(3, e, -1); // top
        setPlane(4, e,  2); // near
        setPlane(5, e, -2); // far
    }

    /** rowIdx: the matrix row to add/subtract from row3 (sign via +/-). */
    private void setPlane(int idx, float[] e, int rowIdx) {
        float sign = (rowIdx < 0) ? -1f : 1f;
        int row = Math.abs(rowIdx);
        float rx = e[row], ry = e[4 + row], rz = e[8 + row], rw = e[12 + row];
        float A = e[3] + sign * rx;
        float B = e[7] + sign * ry;
        float C = e[11] + sign * rz;
        float D = e[15] + sign * rw;
        float len = (float) Math.sqrt(A * A + B * B + C * C);
        if (len > 1e-6f) { A /= len; B /= len; C /= len; D /= len; }
        planes[idx][0] = A; planes[idx][1] = B; planes[idx][2] = C; planes[idx][3] = D;
    }

    /** AABB vs frustum: true when the box intersects (render it). */
    public boolean intersectsAabb(Vector3f center, float hx, float hy, float hz) {
        for (float[] p : planes) {
            float d = p[0] * (center.x + hx * (p[0] >= 0 ? 1 : -1))
                    + p[1] * (center.y + hy * (p[1] >= 0 ? 1 : -1))
                    + p[2] * (center.z + hz * (p[2] >= 0 ? 1 : -1))
                    + p[3];
            if (d < 0) return false;
        }
        return true;
    }
}