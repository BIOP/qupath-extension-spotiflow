"""
Script to evaluate predicted point coordinates against ground truth coordinates using point matching metrics.
The script reads CSV files containing point coordinates from specified directories for ground truth and predictions,
computes matching metrics based on a cutoff distance, and outputs the results to a CSV file if specified.

author: Albert Dominguez Mantes - Weigert Lab - EPFL | @AlbertDominguez

Usage:
    python run-spotiflow-qc.py --ground-truth /path/to/gt_csvs --predictions /path/to/pred_csvs --outfile /path/to/output_metrics.csv
"""

import argparse
from pathlib import Path
from typing import Tuple

import numpy as np
import pandas as pd
from spotiflow.utils import points_matching_dataset, read_coords_csv, read_coords_csv3d


def load_data(
    gt_path: Path, pred_path: Path, is_3d: bool = False
) -> Tuple[Tuple[np.ndarray], Tuple[np.ndarray]]:
    """Load GT and predicted data from the two given folders.

    Args:
        gt_path (Path): Path to the folder containing the ground truth CSV files.
        pred_path (Path): Path to the folder containing the predicted CSV files.
        is_3d (bool, optional): whether the data is 3D or not. Defaults to False.

    Raises:
        ValueError: If the number of ground truth and predicted files do not match.
        ValueError: If the ground truth and predicted files do not match.

    Returns:
        Tuple[Tuple[np.ndarray], Tuple[np.ndarray]]: A tuple containing two tuples of NumPy arrays: the first with the ground truth data and the second with the predicted data.
    """
    gt_path = Path(gt_path)
    pred_path = Path(pred_path)
    gt_files = sorted(gt_path.glob("*.csv"))
    pred_files = sorted(pred_path.glob("*.csv"))

    if len(gt_files) != len(pred_files):
        raise ValueError(
            f"Number of files in GT ({len(gt_files)}) and predictions {len(pred_files)} do not match"
        )

    if not all(
        gt_f.stem.startswith(pred_f.stem.split("_predict")[0])
        for gt_f, pred_f in zip(gt_files, pred_files)
    ):
        raise ValueError(
            "Ground truth and prediction files do not match. Please check the filenames."
        )

    _read_coords_fn = read_coords_csv3d if is_3d else read_coords_csv
    gts = tuple(_read_coords_fn(gt_f) for gt_f in gt_files)
    preds = tuple(_read_coords_fn(pred_f) for pred_f in pred_files)
    return gts, preds


def main():
    parser = argparse.ArgumentParser(
        description="Script to retrieve metrics from prediction CSVs"
    )
    parser.add_argument(
        "-gt",
        "--ground-truth",
        type=str,
        required=True,
        help="Path containing ground truth CSVs",
    )
    parser.add_argument(
        "-p",
        "--predictions",
        type=str,
        required=True,
        help="Path containing predicted CSVs",
    )
    parser.add_argument(
        "-o",
        "--outfile",
        type=Path,
        required=False,
        default=None,
        help="Path to write the predictions to",
    )
    parser.add_argument(
        "--cutoff",
        type=float,
        default=3.0,
        help="Cutoff distance for points matching (in pixels). Defaults to 3px.",
    )
    args = parser.parse_args()

    gts, preds = load_data(args.ground_truth, args.predictions)

    metrics = points_matching_dataset(
        gts,
        preds,
        cutoff_distance=args.cutoff,
        by_image=True,
    )
    metrics_df = pd.DataFrame(vars(metrics), index=[0])

    if args.outfile is not None:
        args.outfile.parent.mkdir(parents=True, exist_ok=True)
        metrics_df.to_csv(args.outfile, index=False)


if __name__ == "__main__":
    main()
