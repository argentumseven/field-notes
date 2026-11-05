use super::*;

pub(super) struct Normalized {
    pub bytes: Vec<u8>,
    pub width: u32,
    pub height: u32,
}
/// Both ordinary uploads and recovered/imported images pass the same decoder,
/// pixel/allocation bounds and metadata-stripping PNG normalization.
pub(super) fn normalize(bytes: &[u8], maximum: usize) -> Result<Normalized> {
    if bytes.len() > maximum {
        return fail(413, "Image exceeds its upload limit");
    }
    let mut reader = image::ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| Fault(400, "Invalid image".into()))?;
    if !matches!(
        reader.format(),
        Some(image::ImageFormat::Png | image::ImageFormat::Jpeg | image::ImageFormat::WebP)
    ) {
        return fail(400, "Use PNG, JPEG, or WebP");
    }
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(4096);
    limits.max_image_height = Some(4096);
    limits.max_alloc = Some(64 * 1024 * 1024);
    reader.limits(limits);
    let image = reader
        .decode()
        .map_err(|_| Fault(400, "Image is invalid or exceeds decode limits".into()))?;
    let mut output = Cursor::new(Vec::new());
    image
        .write_to(&mut output, image::ImageFormat::Png)
        .map_err(|_| Fault(400, "Image normalization failed".into()))?;
    if output.get_ref().len() > 8 * 1024 * 1024 {
        return fail(413, "Normalized image exceeds 8 MiB");
    }
    Ok(Normalized {
        bytes: output.into_inner(),
        width: image.width(),
        height: image.height(),
    })
}
