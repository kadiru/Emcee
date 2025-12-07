using UnityEngine;

[RequireComponent(typeof(Renderer))]
public class FlipVideoY : MonoBehaviour
{
    private Renderer _renderer;

    void Awake()
    {
        _renderer = GetComponent<Renderer>();
    }

    void LateUpdate()
    {
        if (_renderer == null) return;

        // Always work on the *instance* material
        var m = _renderer.material;
        if (m == null) return;

        // Force a vertical flip
        var scale = m.mainTextureScale;
        scale.y = -Mathf.Abs(scale.y);   // make sure it's negative
        m.mainTextureScale = scale;

        // Shift back into view
        var offset = m.mainTextureOffset;
        offset.y = 1f;
        m.mainTextureOffset = offset;
    }
}
